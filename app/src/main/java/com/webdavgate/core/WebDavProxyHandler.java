package com.webdavgate.core;

import org.apache.http.Header;
import org.apache.http.HttpEntity;
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

import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * HTTP 透传处理器（网关模式）：
 * <ul>
 *   <li>302 模式：通过 Cloudflare 302/307 重定向获取目标 STUN 地址</li>
 *   <li>TXT 模式：通过 DNS TXT 记录获取目标 STUN 地址</li>
 * </ul>
 * <p>核心功能：将所有 HTTP 请求（包括 WebDAV）转发到配置的 8888 端口，实现内网访问外网服务。
 */
public class WebDavProxyHandler implements HttpRequestHandler {

    private static final String TAG = "ProxyHandler";

    private final RedirectForwarder mForwarder;
    private final GatewayNode mNode;

    public WebDavProxyHandler(RedirectForwarder forwarder, GatewayNode node) {
        this.mForwarder = forwarder;
        this.mNode = node;
    }

    private static final String[] WEBDAV_METHODS = {"PROPFIND", "MKCOL", "COPY", "MOVE", "LOCK", "UNLOCK", "REPORT", "CHECKOUT", "MINPROPERTIES", "SEARCH", "MKACTIVITY", "UPDATE", "ACL", "OPTIONS"};

    private boolean isWebDavRequest(HttpRequest request) {
        String method = request.getRequestLine().getMethod();
        if (method == null) return false;
        for (String m : WEBDAV_METHODS) {
            if (m.equals(method)) return true;
        }
        return false;
    }

    @Override
    public void handle(HttpRequest request, HttpResponse response, HttpContext context)
            throws HttpException, IOException {

        String method = request.getRequestLine().getMethod();
        String path = request.getRequestLine().getUri();

        LogStore.d(TAG, "Gateway mode: method=" + method + " path=" + path);

        Response upstream;
        long startMs = System.currentTimeMillis();

        try {
            upstream = mForwarder.forward(method, path, null, null, -1);
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
        response.setReasonPhrase(reason);

        for (String name : upstream.headers().names()) {
            for (String v : upstream.headers(name)) {
                response.addHeader(name, v);
            }
        }

        ResponseBody upBody = upstream.body();
        if (upBody != null) {
            byte[] bytes;
            try {
                bytes = upBody.bytes();
            } catch (Exception e) {
                LogStore.w(TAG, "Failed to read body bytes: " + e.getMessage());
                bytes = new byte[0];
            }
            if (bytes != null && bytes.length > 0) {
                response.setEntity(new InputStreamEntity(new java.io.ByteArrayInputStream(bytes), bytes.length));
            } else if (upstream.code() >= 200 && upstream.code() < 300) {
                LogStore.d(TAG, "upstream has no body but status=" + upstream.code() + ", checking for HTML content");
                String htmlContent = upBody.string();
                if (htmlContent != null && htmlContent.length() > 0) {
                    LogStore.d(TAG, "Detected HTML content, forwarding");
                    response.setEntity(new InputStreamEntity(new java.io.ByteArrayInputStream(htmlContent.getBytes()), htmlContent.length()));
                } else {
                    LogStore.d(TAG, "No HTML content to forward");
                }
            }
        }

        upstream.close();
    }
}
