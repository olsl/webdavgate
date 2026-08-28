package com.webdavgate.core;

import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.HttpEntityEnclosingRequest;
import org.apache.http.HttpException;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.entity.InputStreamEntity;
import org.apache.http.protocol.HttpContext;
import org.apache.http.protocol.HttpRequestHandler;

import com.webdavgate.log.LogStore;
import com.webdavgate.model.GatewayNode;

import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;

import okhttp3.Headers;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * HTTP 透传处理器，支持两种模式：
 * <ul>
 *   <li>302 模式：通过 Cloudflare 302/307 重定向解析 STUN 地址</li>
 *   <li>TXT 模式：通过 DNS TXT 记录直接获取 STUN 地址，透明代理转发</li>
 * </ul>
 *
 * <p>支持所有 HTTP 方法（含 WebDAV 扩展 PROPFIND/MKCOL/COPY/MOVE 等）。
 */
public class WebDavProxyHandler implements HttpRequestHandler {

    private static final String TAG = "ProxyHandler";

    private final RedirectForwarder mForwarder;
    private final DnsTxtResolver mTxtResolver;
    private final GatewayNode mNode;

    public WebDavProxyHandler(RedirectForwarder forwarder, DnsTxtResolver txtResolver, GatewayNode node) {
        this.mForwarder = forwarder;
        this.mTxtResolver = txtResolver;
        this.mNode = node;
    }

    @Override
    public void handle(HttpRequest request, HttpResponse response, HttpContext context)
            throws HttpException, IOException {

        String method = request.getRequestLine().getMethod();
        String path = request.getRequestLine().getUri();

        Headers.Builder hb = new Headers.Builder();
        for (Header h : request.getAllHeaders()) {
            hb.add(h.getName(), h.getValue());
        }
        Headers reqHeaders = hb.build();

        InputStream bodyStream = null;
        long contentLength = -1;
        HttpEntity entity = null;
        if (request instanceof HttpEntityEnclosingRequest) {
            entity = ((HttpEntityEnclosingRequest) request).getEntity();
        }
        if (entity != null) {
            InputStream s = entity.getContent();
            if (s != null) {
                bodyStream = s;
                contentLength = entity.getContentLength();
            }
        }

        Response upstream;
        long startMs = System.currentTimeMillis();
        int discoveryMethod = mNode.getDiscoveryMethod();

        try {
            if (discoveryMethod == GatewayNode.DISCOVERY_TXT) {
                // TXT 模式：直接解析 DNS 记录获取 STUN 地址
                String domain = getTsqDomain();
                String stunOrigin = mTxtResolver.resolveViaTxt(domain);
                if (stunOrigin == null) {
                    // TXT 解析失败，回退到 302 模式
                    LogStore.w(TAG, "TXT resolve failed, falling back to redirect mode");
                    upstream = mForwarder.forward(method, path, reqHeaders, bodyStream, contentLength);
                } else {
                    LogStore.d(TAG, "TXT mode: " + method + " " + path + " → " + stunOrigin + " (Host: " + domain + ")");
                    // Host 用配置的入口域名，lucky 等反向代理按域名路由
                    upstream = mForwarder.forwardDirect(method, path, reqHeaders, bodyStream, contentLength, stunOrigin, domain);
                }
            } else if (discoveryMethod == GatewayNode.DISCOVERY_AUTO) {
                // AUTO 模式：先试 TXT，失败回退
                String domain = getTsqDomain();
                String stunOrigin = mTxtResolver.resolveViaTxt(domain);
                if (stunOrigin != null) {
                    upstream = mForwarder.forwardDirect(method, path, reqHeaders, bodyStream, contentLength, stunOrigin, domain);
                } else {
                    upstream = mForwarder.forward(method, path, reqHeaders, bodyStream, contentLength);
                }
            } else {
                // REDIRECT 模式（默认）
                upstream = mForwarder.forward(method, path, reqHeaders, bodyStream, contentLength);
            }
        } catch (IOException e) {
            LogStore.e(TAG, "Forward failed: " + e.getMessage(), e);
            response.setStatusCode(HttpStatus.SC_BAD_GATEWAY);
            String msg = "WebDavGate upstream error: " + e.getMessage();
            response.setEntity(new InputStreamEntity(
                    new java.io.ByteArrayInputStream(msg.getBytes()), msg.length()));
            return;
        }

        long elapsedMs = System.currentTimeMillis() - startMs;
        LogStore.d(TAG, "-> " + upstream.code() + " " + method + " " + path + " (" + elapsedMs + "ms)");

        response.setStatusCode(upstream.code());
        String reason = upstream.message();
        if (reason != null && !reason.isEmpty()) {
            response.setReasonPhrase(reason);
        }

        for (String name : upstream.headers().names()) {
            if (isResponseHopByHop(name) || "Content-Length".equalsIgnoreCase(name)) {
                continue;
            }
            for (String v : upstream.headers(name)) {
                response.addHeader(name, v);
            }
        }

        final ResponseBody upBody = upstream.body();
        if (upBody != null) {
            long len = upBody.contentLength();
            InputStream raw = upBody.byteStream();

            if (len <= 0) {
                try {
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    byte[] buf = new byte[65536];
                    int read;
                    while ((read = raw.read(buf)) != -1) {
                        baos.write(buf, 0, read);
                    }
                    byte[] bytes = baos.toByteArray();
                    response.setEntity(new org.apache.http.entity.ByteArrayEntity(bytes));
                    LogStore.d(TAG, "buffered response: " + bytes.length + " bytes");
                    try { raw.close(); } catch (Exception ignored) {}
                    upstream.close();
                } catch (Exception e) {
                    LogStore.w(TAG, "Failed to buffer response, fallback to stream: " + e.getMessage());
                    InputStream closing = wrapClosing(raw, upstream);
                    response.setEntity(new InputStreamEntity(closing, -1));
                }
            } else {
                InputStream closing = wrapClosing(raw, upstream);
                response.setEntity(new InputStreamEntity(closing, len));
            }
        } else {
            upstream.close();
        }
    }

    /** 获取用于 TXT 查询的域名（容错：自动去掉误填的 http:// 或 https:// 前缀及路径） */
    private String getTsqDomain() {
        String domain = mNode.getStunDomain();
        if (domain != null && !domain.isEmpty()) {
            int schemeIdx = domain.indexOf("://");
            if (schemeIdx >= 0) {
                domain = domain.substring(schemeIdx + 3);
            }
            int slashIdx = domain.indexOf('/');
            if (slashIdx > 0) {
                domain = domain.substring(0, slashIdx);
            }
            return domain.trim();
        }
        // 从 cfUrl 提取域名
        String cfUrl = mNode.getCfUrl();
        if (cfUrl != null) {
            int schemeEnd = cfUrl.indexOf("://");
            if (schemeEnd >= 0) {
                int hostEnd = cfUrl.indexOf('/', schemeEnd + 3);
                String host = hostEnd < 0 ? cfUrl.substring(schemeEnd + 3) : cfUrl.substring(schemeEnd + 3, hostEnd);
                // 去掉端口
                int portIdx = host.indexOf(':');
                if (portIdx > 0) {
                    host = host.substring(0, portIdx);
                }
                return host;
            }
        }
        return "";
    }

    private static InputStream wrapClosing(InputStream raw, Response upstream) {
        return new FilterInputStream(raw) {
            private boolean closed = false;
            @Override
            public void close() throws IOException {
                if (!closed) {
                    closed = true;
                    try { super.close(); }
                    finally { upstream.close(); }
                }
            }
        };
    }

    private static boolean isResponseHopByHop(String name) {
        if (name == null) return false;
        switch (name.toLowerCase(Locale.ROOT)) {
            case "connection":
            case "keep-alive":
            case "proxy-authenticate":
            case "proxy-authorization":
            case "te":
            case "trailers":
            case "transfer-encoding":
            case "upgrade":
                return true;
            default:
                return false;
        }
    }
}
