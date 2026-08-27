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

import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;

import okhttp3.Headers;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * WebDAV 透传处理器（Apache HttpCore 的 {@link HttpRequestHandler}）。
 *
 * <p>它被注册在 "*" 通配路由上，接收<b>所有</b>请求，包括 WebDAV 扩展方法：
 * PROPFIND / MKCOL / COPY / MOVE / LOCK / UNLOCK / PROPPATCH。
 * （这正是选用 HttpCore 而非 AndServer 高层注解的原因 —— 后者的 HttpMethod 枚举
 *   会在 {@code reverse()} 时对这些方法抛 UnsupportedOperationException。）
 *
 * <p>处理流程：
 * <ol>
 *   <li>读取原始方法、路径、请求头；</li>
 *   <li>若请求带实体，取出请求体流；</li>
 *   <li>交给 {@link RedirectForwarder} 消化 302/307 并得到最终响应；</li>
 *   <li>把最终响应的状态码、响应头、响应体流<b>原样</b>回写给客户端。</li>
 * </ol>
 *
 * <p><b>响应生命周期的关键点：</b>HttpCore 的 {@link HttpService} 在本方法返回<b>之后</b>
 * 才把响应实体序列化写回 socket。因此 OkHttp 的 {@link Response} 不能在此处关闭，
 * 否则其响应体流会在写出前就被关闭。这里用一个 {@link FilterInputStream} 包住响应流，
 * 当 HttpCore 写完实体并关闭该流时，连带关闭 OkHttp Response，从而安全释放资源。
 */
public class WebDavProxyHandler implements HttpRequestHandler {

    private static final String TAG = "ProxyHandler";

    private final RedirectForwarder mForwarder;

    public WebDavProxyHandler(RedirectForwarder forwarder) {
        this.mForwarder = forwarder;
    }

    @Override
    public void handle(HttpRequest request, HttpResponse response, HttpContext context)
            throws HttpException, IOException {

        // 1) 原始方法与路径（路径含 query，直接拼到 CF 基础地址后）
        String method = request.getRequestLine().getMethod();
        String path = request.getRequestLine().getUri();

        // 2) 收集请求头，原样透传给上游（含 Content-Type；Content-Length 由 OkHttp 重算，已在转发器剔除）
        Headers.Builder hb = new Headers.Builder();
        for (Header h : request.getAllHeaders()) {
            hb.add(h.getName(), h.getValue());
        }
        Headers reqHeaders = hb.build();

        // 3) 取请求体流（GatewayServer 用 BasicHttpEntityEnclosingRequest，
        //    实现了 HttpEntityEnclosingRequest，可直接 getEntity()）
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

        // 4) 转发（含 302 消化）
        Response upstream;
        long startMs = System.currentTimeMillis();
        LogStore.d(TAG, "-> " + method + " " + path + " (headers=" + reqHeaders.size() + ", body=" + (bodyStream != null) + ", len=" + contentLength + ")" );
        try {
            upstream = mForwarder.forward(method, path, reqHeaders, bodyStream, contentLength);
        } catch (IOException e) {
            // 上游不可达时回 502，并把错误信息写进响应体，便于 CX 端报错排查
            LogStore.e(TAG, "Forward failed: " + e.getMessage(), e);
            response.setStatusCode(HttpStatus.SC_BAD_GATEWAY);
            String msg = "WebDavGate upstream error: " + e.getMessage();
            response.setEntity(new InputStreamEntity(
                    new java.io.ByteArrayInputStream(msg.getBytes()), msg.length()));
            return;
        }

        long elapsedMs = System.currentTimeMillis() - startMs;
        LogStore.d(TAG, "-> " + upstream.code() + " " + method + " " + path + " (" + elapsedMs + "ms)");

        // 5) 状态码原样回写
        response.setStatusCode(upstream.code());
        // reason phrase（如 "207 Multi-Status"）也透传，保持 WebDAV 语义
        String reason = upstream.message();
        if (reason != null && !reason.isEmpty()) {
            response.setReasonPhrase(reason);
        }

        // 6) 透传响应头（剔除 Hop-by-Hop 头与 Content-Length，交给 HttpCore 自行管理）
        //    注意：WebDAV 的 DAV、Allow、Depth、Lock-Token 等头必须原样透传，这里只做白名单式过滤
        for (String name : upstream.headers().names()) {
            if (isResponseHopByHop(name) || "Content-Length".equalsIgnoreCase(name)) {
                continue;
            }
            for (String v : upstream.headers(name)) {
                response.addHeader(name, v);
            }
        }

        // 7) 透传响应体流
        final ResponseBody upBody = upstream.body();
        if (upBody != null) {
            long len = upBody.contentLength();
            InputStream raw = upBody.byteStream();

            if (len <= 0) {
                // 未知长度：缓冲到内存，避免 chunked 传输导致 keep-alive 问题
                // PROPFIND 等小响应通常 < 1MB，GET 大文件走 len > 0 分支
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
                    // 关闭原始流以释放 OkHttp 连接
                    try { raw.close(); } catch (Exception ignored) {}
                    upstream.close();
                } catch (Exception e) {
                    LogStore.w(TAG, "Failed to buffer response, fallback to stream: " + e.getMessage());
                    InputStream closing = wrapClosing(raw, upstream);
                    response.setEntity(new InputStreamEntity(closing, -1));
                }
            } else {
                // 已知长度：直接流式透传
                InputStream closing = wrapClosing(raw, upstream);
                response.setEntity(new InputStreamEntity(closing, len));
            }
        } else {
            // 无响应体（如 204/304）也要确保 OkHttp Response 被释放
            upstream.close();
        }
    }

    /** 包装流，关闭时连带关闭 OkHttp Response */
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

    /**
     * 响应方向的 Hop-by-Hop 过滤。Content-Length 由 HttpCore 依据实体重新计算，
     * 不能用上游的值，否则与实际字节数不符会破坏连接。
     */
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
