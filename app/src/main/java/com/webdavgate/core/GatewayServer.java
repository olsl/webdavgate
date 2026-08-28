package com.webdavgate.core;

import fi.iki.elonen.NanoHTTPD;
import fi.iki.elonen.NanoHTTPD.Response;
import fi.iki.elonen.NanoHTTPD.Response.Status;

import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.HttpVersion;
import org.apache.http.message.BasicHttpEntityEnclosingRequest;
import org.apache.http.message.BasicHttpResponse;
import org.apache.http.protocol.BasicHttpContext;
import org.apache.http.protocol.HttpContext;
import org.apache.http.protocol.HttpRequestHandler;

import com.webdavgate.log.LogStore;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

/**
 * 本地网关服务器，基于 NanoHTTPD。
 *
 * <p>NanoHTTPD 是专为嵌入式场景设计的轻量级 HTTP 服务器，
 * 自动处理 keep-alive、chunked 编码、连接管理，比原生 Socket 更稳定流畅。
 *
 * <p>支持所有 HTTP 方法（含 WebDAV 扩展 PROPFIND/MKCOL/COPY/MOVE/LOCK/UNLOCK/PROPPATCH）。
 */
public class GatewayServer extends NanoHTTPD {

    private static final String TAG = "GatewayServer";

    private final HttpRequestHandler mHandler;
    private volatile boolean mRunning;

    public GatewayServer(int port, HttpRequestHandler handler) {
        super(port);
        this.mHandler = handler;
    }

    /** 启动监听 */
    public void startup() throws IOException {
        if (mRunning) return;
        // 使用 5 秒 accept 超时（而非 0 = 无限），确保 accept 线程可以响应中断
        // 和从异常中恢复。NanoHTTPD 用 0 时 accept() 永久阻塞，无法检测线程死亡。
        start(5000, false);
        LogStore.i(TAG, "Server listening on 127.0.0.1:" + getListeningPort());
        mRunning = true;
    }

    /** 停止监听 */
    public void shutdown() {
        mRunning = false;
        LogStore.i(TAG, "Server shutting down on port " + getListeningPort());
        stop();
    }

    public boolean isRunning() {
        return mRunning;
    }

    /** 实际监听端口 */
    public int getBoundPort() {
        return getListeningPort();
    }

    // ------------------------------------------------------------------
    // NanoHTTPD 核心方法：处理所有 HTTP 请求
    // ------------------------------------------------------------------

    @Override
    public Response serve(IHTTPSession session) {
        String method = session.getMethod().name();
        String uri = session.getUri();

        LogStore.d(TAG, "serve: " + method + " " + uri);

        // 标记是否为流式请求，用于异常时关闭 InputStream
        InputStream streamingInputStream = null;

        try {
            // 1) 构造 BasicHttpEntityEnclosingRequest
            BasicHttpEntityEnclosingRequest request = new BasicHttpEntityEnclosingRequest(method, uri);

            // 2) 复制请求头
            Map<String, String> headers = session.getHeaders();
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                request.addHeader(entry.getKey(), entry.getValue());
            }

            // 2.5) 处理 Expect: 100-continue
            String expect = headers.get("expect");
            if (expect != null && expect.toLowerCase().contains("100-continue")) {
                send100Continue(session);
            }

            // 3) 读取请求 body（如果有）
            String contentLengthStr = headers.get("content-length");
            long contentLength = 0;
            if (contentLengthStr != null) {
                try {
                    contentLength = Long.parseLong(contentLengthStr);
                } catch (NumberFormatException ignored) { }
            }

            // DEBUG: 打印 PUT 请求所有头，定位 body 不到达的原因
            if ("PUT".equalsIgnoreCase(method)) {
                StringBuilder hdrDump = new StringBuilder("PUT headers: ");
                for (Map.Entry<String, String> e : headers.entrySet()) {
                    hdrDump.append(e.getKey()).append("=").append(e.getValue()).append(" | ");
                }
                hdrDump.append(" contentLength=").append(contentLength);
                LogStore.d(TAG, hdrDump.toString());
            }

            // 小请求（< 1MB）缓存到内存，支持重定向重试
            // 大请求直接流式转发，避免 OOM 和卡顿
            long STREAM_THRESHOLD = 1024 * 1024; // 1MB

            if (contentLength > 0) {
                String contentType = headers.get("content-type");
                if (contentLength <= STREAM_THRESHOLD) {
                    // 小文件：缓存到内存
                    byte[] bodyBytes = readBody(session.getInputStream(), contentLength);
                    if (bodyBytes != null && bodyBytes.length > 0) {
                        org.apache.http.entity.ByteArrayEntity entity =
                                new org.apache.http.entity.ByteArrayEntity(bodyBytes);
                        if (contentType != null) {
                            entity.setContentType(contentType);
                        }
                        request.setEntity(entity);
                    }
                } else {
                    // 大文件：流式透传，不缓存
                    streamingInputStream = session.getInputStream();
                    org.apache.http.entity.InputStreamEntity entity =
                            new org.apache.http.entity.InputStreamEntity(
                                    streamingInputStream, contentLength);
                    if (contentType != null) {
                        entity.setContentType(contentType);
                    }
                    request.setEntity(entity);
                    LogStore.d(TAG, "streaming upload: " + contentLength + " bytes");
                }
            }

            // 4) 调用 handler
            BasicHttpResponse response = new BasicHttpResponse(HttpVersion.HTTP_1_1, 200, "OK");
            HttpContext ctx = new BasicHttpContext();

            try {
                mHandler.handle(request, response, ctx);
                LogStore.d(TAG, "handler done, status=" + response.getStatusLine().getStatusCode());
            } catch (Exception e) {
                LogStore.w(TAG, "handler error: " + e.getMessage(), e);
                response = new BasicHttpResponse(HttpVersion.HTTP_1_1, 500, "Internal Server Error");
                response.setEntity(new org.apache.http.entity.StringEntity(
                        "WebDavGate handler error: " + e.getMessage(), "UTF-8"));
            }

            // 5) 转换为 NanoHTTPD Response
            return toNanoResponse(response);

        } catch (Exception e) {
            LogStore.w(TAG, "serve error: " + e.getMessage(), e);
            return newFixedLengthResponse(Status.INTERNAL_ERROR, "text/plain",
                    "WebDavGate error: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // 内部辅助方法
    // ------------------------------------------------------------------

    /**
     * 读取请求 body 到 byte[]。
     */
    private byte[] readBody(InputStream in, long contentLength) throws IOException {
        if (contentLength <= 0) return null;

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long remaining = contentLength;
        int read;
        while (remaining > 0 && (read = in.read(buffer, 0, (int) Math.min(buffer.length, remaining))) > 0) {
            baos.write(buffer, 0, read);
            remaining -= read;
        }
        return baos.toByteArray();
    }

    /**
     * 将 Apache HttpResponse 转换为 NanoHTTPD Response。
     * 支持流式输出（大文件），避免 OOM。
     * 正确复制所有响应头（WebDAV 需要 DAV/Allow/Depth 等头）。
     * 正确处理 Content-Length 以支持 keep-alive。
     */
    private Response toNanoResponse(HttpResponse response) {
        int statusCode = response.getStatusLine().getStatusCode();

        // 查找状态
        Status status = Status.lookup(statusCode);
        if (status == null) {
            status = Status.INTERNAL_ERROR;
        }

        HttpEntity entity = response.getEntity();

        // 1) 先创建基础 Response
        Response nanoResponse;
        String contentType = "application/octet-stream";

        if (entity != null && entity.getContentType() != null) {
            contentType = entity.getContentType().getValue();
        }

        if (entity != null) {
            try {
                InputStream bodyStream = entity.getContent();
                long contentLength = entity.getContentLength();

                if (contentLength > 0 && bodyStream != null) {
                    // 固定长度响应，支持流式 + keep-alive
                    nanoResponse = newFixedLengthResponse(status, contentType, bodyStream, contentLength);
                } else if (bodyStream != null) {
                    // 未知长度，使用 chunked 传输
                    nanoResponse = newChunkedResponse(status, contentType, bodyStream);
                } else {
                    nanoResponse = newFixedLengthResponse(status, contentType, "");
                }
            } catch (Exception e) {
                LogStore.w(TAG, "toNanoResponse: error getting content: " + e.getMessage());
                nanoResponse = newFixedLengthResponse(Status.INTERNAL_ERROR, "text/plain",
                        "Error reading response: " + e.getMessage());
            }
        } else {
            // 无 body 的响应（如 204/304）
            nanoResponse = newFixedLengthResponse(status, contentType, "");
        }

        // 2) 复制所有响应头（除了 hop-by-hop 和 content-length/transfer-encoding）
        for (org.apache.http.Header header : response.getAllHeaders()) {
            String name = header.getName();
            if (!isHopByHop(name) && !"Content-Length".equalsIgnoreCase(name)
                    && !"Transfer-Encoding".equalsIgnoreCase(name)) {
                nanoResponse.addHeader(name, header.getValue());
            }
        }

        return nanoResponse;
    }

    /** Hop-by-Hop 头过滤 */
    private static boolean isHopByHop(String name) {
        if (name == null) return false;
        switch (name.toLowerCase(java.util.Locale.ROOT)) {
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

    /**
     * 发送 HTTP/1.1 100 Continue 响应。
     * NanoHTTPD 2.3.1 不自动处理 Expect: 100-continue，客户端等待 100 才发 body，
     * 导致 readBody 超时。通过反射获取 HTTPSession 的 outputStream 发送 100。
     */
    private void send100Continue(IHTTPSession session) {
        try {
            java.lang.reflect.Field f = session.getClass().getDeclaredField("outputStream");
            f.setAccessible(true);
            Object out = f.get(session);
            if (out instanceof java.io.OutputStream) {
                java.io.OutputStream os = (java.io.OutputStream) out;
                os.write("HTTP/1.1 100 Continue\r\n\r\n".getBytes("UTF-8"));
                os.flush();
                LogStore.d(TAG, "Sent 100 Continue");
            } else {
                LogStore.w(TAG, "send100Continue: outputStream is " + (out == null ? "null" : out.getClass().getName()));
            }
        } catch (Exception e) {
            LogStore.w(TAG, "send100Continue failed: " + e.getMessage());
        }
    }
}
