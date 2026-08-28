package com.webdavgate.core;

import com.webdavgate.log.LogStore;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import okhttp3.Headers;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * 核心转发器：负责向 Cloudflare 302 入口发起请求，并手动消化 302/307 跳转，
 * 最终用【原始请求方法 + 原始请求体】向 STUN 直连地址发起请求。
 *
 * <p>为什么不使用 OkHttp 自带的自动重定向？
 * <ul>
 *   <li>OkHttp 在遇到 302 时会把非 GET/HEAD 方法降级成 GET（符合 RFC 7231 的历史语义），
 *       这会直接破坏 WebDAV —— 例如 PROPFIND 会被错误地改成 GET，目录列举彻底失效。</li>
 *   <li>307 虽然保留方法，但 OkHttp 的自动重定向同样会重新打开 body 流，无法保证大文件可重发。</li>
 *   <li>因此必须 {@code followRedirects(false)}，手动处理，以保证方法与请求体原封不动地透传。</li>
 * </ul>
 *
 * <p>请求体缓存策略：重定向意味着同一份 body 要发送两次（先给 CF，再给 STUN）。
 * 流是不可重放的，所以首次接收到 body 时必须缓存成"可重复读"的载体：
 * <ul>
 *   <li>小体（已知长度 ≤ 阈值）：缓存进内存 byte[]；</li>
 *   <li>大体或长度未知：落盘到临时文件，发送时按文件读，避免 OOM。</li>
 * </ul>
 */
public class RedirectForwarder {

    private static final String TAG = "Forwarder";

    /** CF 302 入口地址（含基础路径），CX 请求的 path 会被拼接到它后面 */
    private final String mCfBaseUrl;

    /** 独立 client，关闭自动重定向、读超时拉到无限大以支持大文件上传 */
    private final OkHttpClient mClient;

    /** 最大重定向次数，防止跳转环路死循环 */
    private static final int MAX_REDIRECTS = 5;

    /** 超过该阈值则请求体落盘缓存，避免内存溢出 */
    private static final long MEMORY_BUFFER_LIMIT = 1L * 1024 * 1024; // 1MB

    /**
     * 重定向缓存：CF 域名（host[:port]） → STUN 直连 origin（scheme://host[:port]）
     *
     * <p>命中后跳过 CF 一跳，直接打 STUN，将请求时延从 4s+ 降到 100ms 级别，
     * 避免客户端（CX OkHttp 默认 10s 读超时）等不到响应而断开。
     *
     * <p>失败回退：直连出错则清空对应缓存项，回退到走 CF 的正常流程，重新学习。
     */
    private final java.util.concurrent.ConcurrentHashMap<String, String> mRedirectCache = new java.util.concurrent.ConcurrentHashMap<>();

    /** 缓存项有效期（毫秒）。STUN 地址可能动态变化，过期后强制走 CF 重新学习 */
    private static final long CACHE_TTL_MS = 2 * 60 * 1000; // 2 分钟（缩短，更快适应端口变化）

    /** 缓存值：origin + 时间戳，用数组存避免再定义类 */
    private final java.util.concurrent.ConcurrentHashMap<String, long[]> mCacheTime = new java.util.concurrent.ConcurrentHashMap<>();

    public RedirectForwarder(String cfBaseUrl) {
        // 自动补全 URL scheme（兼容用户省略 https:// 前缀的情况）
        if (cfBaseUrl != null && !cfBaseUrl.contains("://")) {
            cfBaseUrl = "https://" + cfBaseUrl;
        }
        this.mCfBaseUrl = cfBaseUrl;
        // 关闭自动重定向是整个方案成立的关键
        // 自定义 SocketFactory：增大发送/接收缓冲区到 1MB，提升上传/下载吞吐
        javax.net.SocketFactory socketFactory = new javax.net.SocketFactory() {
            @Override
            public java.net.Socket createSocket() throws java.io.IOException {
                java.net.Socket s = new java.net.Socket();
                try { s.setSendBufferSize(1024 * 1024); } catch (java.net.SocketException ignored) { }
                try { s.setReceiveBufferSize(1024 * 1024); } catch (java.net.SocketException ignored) { }
                return s;
            }
            @Override
            public java.net.Socket createSocket(String host, int port) throws java.io.IOException {
                java.net.Socket s = new java.net.Socket();
                try { s.setSendBufferSize(1024 * 1024); } catch (java.net.SocketException ignored) { }
                try { s.setReceiveBufferSize(1024 * 1024); } catch (java.net.SocketException ignored) { }
                s.connect(new java.net.InetSocketAddress(host, port), 15000);
                return s;
            }
            @Override
            public java.net.Socket createSocket(String host, int port, java.net.InetAddress localHost, int localPort) throws java.io.IOException {
                java.net.Socket s = new java.net.Socket();
                try { s.setSendBufferSize(1024 * 1024); } catch (java.net.SocketException ignored) { }
                try { s.setReceiveBufferSize(1024 * 1024); } catch (java.net.SocketException ignored) { }
                s.bind(new java.net.InetSocketAddress(localHost, localPort));
                s.connect(new java.net.InetSocketAddress(host, port), 15000);
                return s;
            }
            @Override
            public java.net.Socket createSocket(java.net.InetAddress host, int port) throws java.io.IOException {
                java.net.Socket s = new java.net.Socket();
                try { s.setSendBufferSize(1024 * 1024); } catch (java.net.SocketException ignored) { }
                try { s.setReceiveBufferSize(1024 * 1024); } catch (java.net.SocketException ignored) { }
                s.connect(new java.net.InetSocketAddress(host, port), 15000);
                return s;
            }
            @Override
            public java.net.Socket createSocket(java.net.InetAddress address, int port, java.net.InetAddress localAddress, int localPort) throws java.io.IOException {
                java.net.Socket s = new java.net.Socket();
                try { s.setSendBufferSize(1024 * 1024); } catch (java.net.SocketException ignored) { }
                try { s.setReceiveBufferSize(1024 * 1024); } catch (java.net.SocketException ignored) { }
                s.bind(new java.net.InetSocketAddress(localAddress, localPort));
                s.connect(new java.net.InetSocketAddress(address, port), 15000);
                return s;
            }
        };
        // 由于强制 Connection: close，连接池实际不会复用连接
        // 保留连接池以支持 retryOnConnectionFailure 的重试场景
        okhttp3.ConnectionPool connectionPool = new okhttp3.ConnectionPool(5, 5, TimeUnit.SECONDS);
        okhttp3.Dispatcher dispatcher = new okhttp3.Dispatcher();
        dispatcher.setMaxRequests(20);
        dispatcher.setMaxRequestsPerHost(20);
        this.mClient = new OkHttpClient.Builder()
                .socketFactory(socketFactory)
                .connectionPool(connectionPool)
                .dispatcher(dispatcher)
                .followRedirects(false)
                .followSslRedirects(false)
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)    // 读超时60秒，支持大文件传输
                .writeTimeout(60, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build();
    }

    /** 从 URL 提取 origin（scheme://host[:port]） */
    private static String originOf(String url) {
        int schemeEnd = url.indexOf("://");
        if (schemeEnd < 0) return null;
        int hostEnd = url.indexOf('/', schemeEnd + 3);
        return hostEnd < 0 ? url : url.substring(0, hostEnd);
    }

    /** 从 URL 提取 host[:port]（用作缓存 key） */
    private static String hostOf(String url) {
        String origin = originOf(url);
        if (origin == null) return null;
        int schemeEnd = origin.indexOf("://");
        return origin.substring(schemeEnd + 3);
    }

    /** 查询缓存：返回 CF host 对应的 STUN origin，未命中或已过期返回 null */
    private String getCachedOrigin(String cfUrl) {
        String host = hostOf(cfUrl);
        if (host == null) return null;
        String origin = mRedirectCache.get(host);
        if (origin == null) return null;
        long[] ts = mCacheTime.get(host);
        if (ts == null || System.currentTimeMillis() - ts[0] > CACHE_TTL_MS) {
            // 过期，清理
            mRedirectCache.remove(host);
            mCacheTime.remove(host);
            return null;
        }
        return origin;
    }

    /** 写入缓存 */
    private void putCachedOrigin(String cfUrl, String stunOrigin) {
        String host = hostOf(cfUrl);
        if (host == null || stunOrigin == null) return;
        mRedirectCache.put(host, stunOrigin);
        mCacheTime.put(host, new long[]{System.currentTimeMillis()});
    }

    /** 失效缓存 */
    private void invalidateCache(String cfUrl) {
        String host = hostOf(cfUrl);
        if (host == null) return;
        mRedirectCache.remove(host);
        mCacheTime.remove(host);
    }

    /**
     * 转发一次请求（302 模式），返回最终的 {@link Response}。
     * <b>调用方负责在消费完响应体后关闭该 Response。</b>
     *
     * @param method       原始 HTTP 方法
     * @param path         CX 请求路径（含 query），拼到 cfBaseUrl 之后
     * @param reqHeaders   需透传的请求头
     * @param body         请求体输入流；无体的方法传 null
     * @param contentLength 请求体长度，-1 表示未知
     */
    public Response forward(String method, String path, Headers reqHeaders,
                            InputStream body, long contentLength) throws IOException {
        return forwardInternal(method, path, reqHeaders, body, contentLength, false);
    }

    /**
     * 转发一次请求（TXT 透明代理模式），直接用已解析的 STUN origin。
     * 不处理重定向，不修改 WebDAV 相关头，纯 HTTP 透传。
     *
     * @param method       原始 HTTP 方法
     * @param path         请求路径（含 query）
     * @param reqHeaders   需透传的请求头
     * @param body         请求体输入流
     * @param contentLength 请求体长度
     * @param stunOrigin   已解析的 STUN 直连 origin（如 http://1.2.3.4:5678）
     * @param hostOverride Host 头覆盖值（如 nas.example.com），
     *                     用于 lucky 等按域名路由的反向代理；传空则用 STUN 地址的 host
     */
    public Response forwardDirect(String method, String path, Headers reqHeaders,
                                  InputStream body, long contentLength,
                                  String stunOrigin, String hostOverride) throws IOException {
        return forwardInternal(method, path, reqHeaders, body, contentLength, true, stunOrigin, hostOverride);
    }

    /** 内部统一转发逻辑 */
    private Response forwardInternal(String method, String path, Headers reqHeaders,
                                     InputStream body, long contentLength,
                                     boolean directMode, String... stunOriginOpt) throws IOException {
        // TXT 模式下的目标 origin 与 Host 覆盖值
        String txtOrigin = directMode && stunOriginOpt.length > 0 ? stunOriginOpt[0] : null;
        String hostOverride = directMode && stunOriginOpt.length > 1 ? stunOriginOpt[1] : null;

        // streaming 模式判定：大文件（> 1MB）走流式上传，避免缓存到内存或磁盘
        boolean streaming = body != null && contentLength > MEMORY_BUFFER_LIMIT;
        LogStore.d(TAG, "forward(" + (directMode ? "TXT" : "302") + "): method=" + method + " streaming=" + streaming
                + " contentLength=" + contentLength);

        // 动态超时策略
        int readTimeoutSec;
        int writeTimeoutSec;
        if (contentLength <= 0) {
            readTimeoutSec = 30;
            writeTimeoutSec = 30;
        } else if (contentLength <= 10 * 1024 * 1024) {
            readTimeoutSec = 30;
            writeTimeoutSec = 60;
        } else if (contentLength <= 100 * 1024 * 1024) {
            readTimeoutSec = 300;
            writeTimeoutSec = 600;
        } else {
            long estimatedSec = Math.max(contentLength / (2 * 1024 * 1024), 900);
            readTimeoutSec = (int) Math.min(estimatedSec + 60, 3600);
            writeTimeoutSec = (int) Math.min(estimatedSec * 2, 7200);
        }

        final okhttp3.OkHttpClient timeoutClient = mClient.newBuilder()
                .readTimeout(readTimeoutSec, TimeUnit.SECONDS)
                .writeTimeout(writeTimeoutSec, TimeUnit.SECONDS)
                .build();

        // 非 streaming 模式：缓存请求体
        BufferedBody buffered = null;
        if (body != null && !streaming) {
            buffered = bufferBody(body, contentLength);
        }

        File spillFile = (buffered instanceof FileBody) ? ((FileBody) buffered).file : null;
        try {
            if (directMode) {
                // ====== TXT 透明代理模式：简化路径 ======
                // 直接用 TXT 解析出的 origin，不处理重定向
                String url = joinUrl(txtOrigin, path);
                LogStore.d(TAG, "TXT mode: forwarding to " + url);

                // TXT 模式下不修改 Destination 等 WebDAV 头（纯 HTTP 透传）
                // Host 头由构建器设置：hostOverride 非空用之（入口域名），否则 OkHttp 按 URL 生成
                Request request = streaming
                        ? buildStreamingRequest(method, url, reqHeaders, body, contentLength, hostOverride)
                        : buildRequest(method, url, reqHeaders, buffered, hostOverride);

                Response response;
                long startTime = System.currentTimeMillis();
                try {
                    response = timeoutClient.newCall(request).execute();
                    long elapsed = System.currentTimeMillis() - startTime;
                    LogStore.d(TAG, "TXT response: " + response.code() + " (" + elapsed + "ms)");
                } catch (IOException e) {
                    LogStore.w(TAG, "TXT forward failed: " + e.getMessage());
                    throw e;
                }

                // 简单的 5xx 重试（最多 2 次）
                int retries = 0;
                while (!streaming && isTransientError(response.code()) && retries < 2) {
                    retries++;
                    LogStore.w(TAG, "TXT transient " + response.code() + ", retry " + retries + "/2");
                    response.close();
                    try { Thread.sleep(100 * retries); } catch (InterruptedException ignored) { }
                    request = buildRequest(method, url, reqHeaders, buffered, hostOverride);
                    response = timeoutClient.newCall(request).execute();
                }
                return response;

            } else {
                // ====== 302 重定向模式：原有逻辑 ======
                String cachedOrigin = getCachedOrigin(mCfBaseUrl);
                String url;
                boolean useCache = cachedOrigin != null;
                if (useCache) {
                    url = joinUrl(cachedOrigin, path);
                    LogStore.d(TAG, "Cache hit: " + method + " " + url);
                } else {
                    url = joinUrl(mCfBaseUrl, path);
                    LogStore.d(TAG, "Hop 0: " + method + " " + url);
                }

                Request request = streaming
                        ? buildStreamingRequest(method, url, reqHeaders, body, contentLength)
                        : buildRequest(method, url, reqHeaders, buffered);

                Response response;
                long startTime = System.currentTimeMillis();
                try {
                    response = timeoutClient.newCall(request).execute();
                    long elapsed = System.currentTimeMillis() - startTime;
                    LogStore.d(TAG, "Response received: " + response.code() + " (" + elapsed + "ms)");
                } catch (IOException e) {
                    LogStore.w(TAG, "Request failed: " + method + " " + url + " - " + e.getMessage());
                    if (useCache) {
                        LogStore.w(TAG, "Cache hit but failed, invalidating cache: " + e.getMessage());
                        invalidateCache(mCfBaseUrl);
                        if (streaming) {
                            LogStore.w(TAG, "Streaming mode, can't fallback, throwing");
                            throw e;
                        } else {
                            LogStore.w(TAG, "Falling back to CF: " + method + " " + mCfBaseUrl);
                            url = joinUrl(mCfBaseUrl, path);
                            request = buildRequest(method, url, reqHeaders, buffered);
                            try {
                                response = timeoutClient.newCall(request).execute();
                                LogStore.d(TAG, "Fallback response: " + response.code());
                            } catch (IOException e2) {
                                LogStore.w(TAG, "Fallback to CF also failed: " + e2.getMessage());
                                throw e2;
                            }
                        }
                    } else {
                        throw e;
                    }
                }

                // 手动消化 302/307
                int redirects = 0;
                while (!streaming && isRedirect(response.code()) && redirects < MAX_REDIRECTS) {
                    String location = response.header("Location");
                    LogStore.i(TAG, "Hop " + redirects + ": " + response.code() + " → " + (location != null ? location : "null"));
                    response.close();
                    if (location == null) break;
                    String nextUrl = resolveLocation(url, location);
                    if (!useCache && redirects == 0) {
                        String stunOrigin = originOf(nextUrl);
                        if (stunOrigin != null) {
                            putCachedOrigin(mCfBaseUrl, stunOrigin);
                            LogStore.i(TAG, "Cache learned: " + hostOf(mCfBaseUrl) + " → " + stunOrigin);
                        }
                    }
                    redirects++;
                    LogStore.d(TAG, "Hop " + redirects + ": " + method + " " + nextUrl);
                    Headers headersForNext = stripCrossOriginHeaders(url, nextUrl, reqHeaders);
                    request = buildRequest(method, nextUrl, headersForNext, buffered);
                    long hopStart = System.currentTimeMillis();
                    response = timeoutClient.newCall(request).execute();
                    LogStore.d(TAG, "Hop " + redirects + " response: " + response.code() + " (" + (System.currentTimeMillis() - hopStart) + "ms)");
                    url = nextUrl;
                }

                if (streaming && isRedirect(response.code())) {
                    LogStore.w(TAG, "Streaming mode got redirect " + response.code() + ", returning to CX");
                }
                LogStore.d(TAG, "Final: " + response.code() + " " + url + " (redirects=" + redirects + ")");

                // 416/5xx 自动重试
                int retries = 0;
                while (!streaming && isTransientError(response.code()) && retries < 2) {
                    boolean isWriteMethod = isWriteMethod(method);
                    if (isWriteMethod && response.code() == 502) {
                        LogStore.w(TAG, "Write method " + method + " got 502, invalidating cache and retrying via CF");
                        response.close();
                        invalidateCache(mCfBaseUrl);
                        url = joinUrl(mCfBaseUrl, path);
                        request = buildRequest(method, url, reqHeaders, buffered);
                        response = timeoutClient.newCall(request).execute();
                        LogStore.d(TAG, "Fallback to CF final: " + response.code() + " " + url);
                        break;
                    }
                    retries++;
                    LogStore.w(TAG, "Transient " + response.code() + ", retry " + retries + "/2");
                    response.close();
                    try { Thread.sleep(100 * retries); } catch (InterruptedException ignored) { }
                    request = buildRequest(method, url, reqHeaders, buffered);
                    response = timeoutClient.newCall(request).execute();
                    LogStore.d(TAG, "Retry " + retries + " final: " + response.code() + " " + url);
                }
                return response;
            }
        } finally {
            if (spillFile != null) {
                spillFile.delete();
            }
        }
    }

    // ------------------------------------------------------------------
    // 以下为内部实现
    // ------------------------------------------------------------------

    /** 构造 OkHttp 请求，处理"方法是否有 body"这一 OkHttp 强约束 */
    private Request buildRequest(String method, String url, Headers reqHeaders,
                                 BufferedBody body) {
        return buildRequest(method, url, reqHeaders, body, null);
    }

    private Request buildRequest(String method, String url, Headers reqHeaders,
                                 BufferedBody body, String hostOverride) {
        Request.Builder rb = new Request.Builder().url(url);

        // 强制 Connection: close，避免复用僵尸连接（STUN 直连服务器空闲后会关闭连接）
        rb.header("Connection", "close");

        boolean hasAcceptEncoding = false;
        boolean hostSet = false;
        String destinationHeader = null;
        for (int i = 0; i < reqHeaders.size(); i++) {
            String name = reqHeaders.name(i);
            // Hop-by-Hop 头与长度头交给 OkHttp 自行管理，不手动覆盖
            if (isHopByHop(name)
                    || "Content-Length".equalsIgnoreCase(name)
                    || "Connection".equalsIgnoreCase(name)) {
                continue;
            }
            // Host：TXT 模式用 hostOverride（入口域名，lucky 按域名路由）；否则 OkHttp 按 URL 生成
            if ("Host".equalsIgnoreCase(name)) {
                if (hostOverride != null && !hostSet) {
                    rb.header("Host", hostOverride);
                    hostSet = true;
                }
                continue;
            }
            // MOVE/COPY 的 Destination 单独记录，最后统一按目标地址改写 origin
            if ("Destination".equalsIgnoreCase(name)) {
                destinationHeader = reqHeaders.value(i);
                continue;
            }
            if ("Accept-Encoding".equalsIgnoreCase(name)) {
                hasAcceptEncoding = true;
            }
            rb.addHeader(name, reqHeaders.value(i));
        }
        if (destinationHeader != null) {
            rb.addHeader("Destination", rewriteDestinationOrigin(url, destinationHeader));
        }
        // 显式声明 Accept-Encoding 以阻止 OkHttp 自动添加 gzip 并自动解压，
        // 从而保证响应体原样透传（Content-Length 与实际字节数一致，不会被 OkHttp 改动）
        if (!hasAcceptEncoding) {
            rb.addHeader("Accept-Encoding", "identity");
        }

        // OkHttp 约束：GET/HEAD 必须传 null body；其余方法即便无体也要传"空体"，
        // 否则 Request.Builder.method 会抛 IllegalArgumentException
        String m = method.toUpperCase(Locale.ROOT);
        RequestBody reqBody;
        if (body != null) {
            reqBody = body.toRequestBody();
        } else if ("GET".equals(m) || "HEAD".equals(m)) {
            reqBody = null;
        } else {
            // MKCOL/COPY/MOVE/UNLOCK/DELETE/OPTIONS 等无体方法走这里
            // create(MediaType, byte[])：第一参为 contentType，传 null 沿用已复制的头
            reqBody = RequestBody.create(null, new byte[0]);
        }
        rb.method(method, reqBody);
        return rb.build();
    }

    /**
     * 构造流式上传请求：用自定义 RequestBody 包装 InputStream，
     * OkHttp 调用 writeTo(sink) 时按需从 InputStream 读 64KB 写到 socket，
     * 实现"边读 CX body 边转发到 NAS"，避免大文件 OOM 或先缓存到磁盘。
     */
    private Request buildStreamingRequest(String method, String url, Headers reqHeaders,
                                          InputStream body, long contentLength) {
        return buildStreamingRequest(method, url, reqHeaders, body, contentLength, null);
    }

    private Request buildStreamingRequest(String method, String url, Headers reqHeaders,
                                          InputStream body, long contentLength, String hostOverride) {
        Request.Builder rb = new Request.Builder().url(url);

        // 强制 Connection: close，避免复用僵尸连接
        rb.header("Connection", "close");

        boolean hostSet = false;
        String destinationHeader = null;
        for (int i = 0; i < reqHeaders.size(); i++) {
            String name = reqHeaders.name(i);
            if (isHopByHop(name)
                    || "Content-Length".equalsIgnoreCase(name)
                    || "Connection".equalsIgnoreCase(name)) {
                continue;
            }
            // Host：TXT 模式用 hostOverride（入口域名，lucky 按域名路由）；否则 OkHttp 按 URL 生成
            if ("Host".equalsIgnoreCase(name)) {
                if (hostOverride != null && !hostSet) {
                    rb.header("Host", hostOverride);
                    hostSet = true;
                }
                continue;
            }
            if ("Destination".equalsIgnoreCase(name)) {
                destinationHeader = reqHeaders.value(i);
                continue;
            }
            rb.addHeader(name, reqHeaders.value(i));
        }
        if (destinationHeader != null) {
            rb.addHeader("Destination", rewriteDestinationOrigin(url, destinationHeader));
        }

        RequestBody reqBody = new StreamingRequestBody(body, contentLength);
        rb.method(method, reqBody);
        return rb.build();
    }

    /**
     * 重写 WebDAV MOVE/COPY 的 Destination 头 origin。
     *
     * <p>CX 发出请求时，Destination 通常指向 CF 入口（如 https://nas.shdj.cc.cd/dav/x.mp4），
     * 但网关实际把请求打向 STUN 直连（如 http://nas.stun.shdj.cc.cd:1820/dav/...）。
     * 多数 NAS 实现（Apache mod_dav 等）要求 Destination 与当前请求同源，
     * 不一致直接返回 502 Bad Gateway —— 这正是"覆盖上传失败"的根因。
     *
     * <p>处理：保留 Destination 的 path+query 部分原样不动（覆盖语义不变），
     * 仅替换 origin 为当前请求的目标 origin；相对路径形式则补全为绝对 URI。
     */
    private static String rewriteDestinationOrigin(String targetUrl, String destination) {
        String targetOrigin = originOf(targetUrl);
        if (targetOrigin == null) return destination;

        String result;
        if (destination.startsWith("http://") || destination.startsWith("https://")) {
            String destOrigin = originOf(destination);
            if (destOrigin == null) return destination;
            result = destination.equals(destOrigin)
                    ? "/"   // 只有 origin 没有 path 的极端情况
                    : destination.substring(destOrigin.length());
        } else {
            // 相对路径形式（RFC 4918 允许），补全 origin
            result = destination.startsWith("/") ? destination : "/" + destination;
        }
        LogStore.d(TAG, "Destination rewritten: " + destination + " → " + targetOrigin + result);
        return targetOrigin + result;
    }

    /**
     * 自定义 RequestBody：包装 InputStream 流式写入 OkHttp 的 BufferedSink。
     * OkHttp 在上传时调用 writeTo(sink)，本方法按 1MB chunk 边读边写，
     * 不缓存整个 body 到内存或磁盘，避免大文件上传 OOM 或卡顿。
     * 增加线程中断检查，确保客户端断开时能及时终止，不遗留僵尸连接。
     */
    private static class StreamingRequestBody extends RequestBody {
        private final InputStream mStream;
        private final long mLength;

        StreamingRequestBody(InputStream stream, long length) {
            this.mStream = stream;
            this.mLength = length;
        }

        @Override
        public long contentLength() {
            return mLength;
        }

        @Override
        public okhttp3.MediaType contentType() {
            return null; // 沿用已设置的 Content-Type 头
        }

        @Override
        public void writeTo(okio.BufferedSink sink) throws IOException {
            // 1MB buffer：减少 syscall 次数
            byte[] buffer = new byte[1024 * 1024];
            int read;
            long totalWritten = 0;
            while (totalWritten < mLength) {
                // 检查线程是否被中断（客户端断开时 OkHttp 会中断线程）
                if (Thread.currentThread().isInterrupted()) {
                    LogStore.w(TAG, "StreamingRequestBody: thread interrupted, aborting upload");
                    throw new IOException("Upload interrupted by client disconnect");
                }

                read = mStream.read(buffer, 0, (int) Math.min(buffer.length, mLength - totalWritten));
                if (read == -1) {
                    LogStore.w(TAG, "StreamingRequestBody: EOF reached after " + totalWritten + "/" + mLength);
                    break;
                }
                if (read > 0) {
                    sink.write(buffer, 0, read);
                    totalWritten += read;
                }
            }
        }
    }

    /** 判断状态码是否为需要手动重定向的 3xx（301 也一并处理，保持行为一致） */
    private static boolean isRedirect(int code) {
        return code == 301 || code == 302 || code == 303 || code == 307 || code == 308;
    }

    /**
     * 跨域跳转时移除 Authorization 和 Cookie：例如 NAS 302 跳到阿里云 OSS 预签名直链，
     * OSS 用 AWS V4 签名（X-Amz-Signature），收到 NAS 的 Basic Auth 头会直接 400 Bad Request。
     * 同域跳转（如 NAS CF 入口 nas.shdj.cc.cd → NAS STUN 直连 nas.stun.shdj.cc.cd）保留，
     * 因为两者是同一 NAS 的不同入口，都需要 Basic Auth。
     * 判断方法：取原 host 去掉第一个 label 后的注册域，如果新 host 包含该注册域，认为是同域。
     */
    private static Headers stripCrossOriginHeaders(String fromUrl, String toUrl, Headers original) {
        String fromHost = hostOf(fromUrl);
        String toHost = hostOf(toUrl);
        if (fromHost != null && toHost != null) {
            // 取原 host 的注册域（去掉第一个 label）：nas.shdj.cc.cd → shdj.cc.cd
            int firstDot = fromHost.indexOf('.');
            String registeredDomain = firstDot > 0 ? fromHost.substring(firstDot + 1) : fromHost;
            // 注册域必须包含 dot（避免 example.com 误判为同域）
            // 新 host 包含注册域 → 同一服务的不同子域（NAS CF 入口 → NAS STUN 直连），保留 Authorization
            if (registeredDomain.contains(".") && toHost.contains(registeredDomain)) {
                return original;
            }
        }
        // 跨域：移除 Authorization 和 Cookie
        okhttp3.Headers.Builder hb = new okhttp3.Headers.Builder();
        for (int i = 0; i < original.size(); i++) {
            String name = original.name(i);
            if ("Authorization".equalsIgnoreCase(name) || "Cookie".equalsIgnoreCase(name)) {
                continue;
            }
            hb.add(name, original.value(i));
        }
        LogStore.d(TAG, "Cross-origin redirect: stripped Authorization/Cookie (" + fromHost + " → " + toHost + ")");
        return hb.build();
    }

    /**
     * 瞬态错误：可重试的响应码。
     * <ul>
     *   <li>416 Range Not Satisfiable：lucky STUN 通道刚建立时偶发，重试即恢复</li>
     *   <li>502/503/504：上游网关或 NAS 瞬态不可用</li>
     *   <li>500：NAS 偶发内部错误</li>
     * </ul>
     */
    private static boolean isTransientError(int code) {
        return code == 416 || code == 500 || code == 502 || code == 503 || code == 504;
    }

    /**
     * 判断是否为写操作（会修改服务器状态）
     * 写操作在遇到 502 时回退到 CF 重试
     */
    private static boolean isWriteMethod(String method) {
        if (method == null) return false;
        String m = method.toUpperCase(Locale.ROOT);
        return m.equals("PUT") || m.equals("DELETE") || m.equals("MKCOL")
                || m.equals("COPY") || m.equals("MOVE") || m.equals("PROPPATCH")
                || m.equals("LOCK") || m.equals("UNLOCK");
    }

    /** 拼接 CF 基础地址与 CX 请求路径 */
    private static String joinUrl(String base, String path) {
        if (path == null || path.isEmpty() || path.equals("/")) {
            return base;
        }
        String b = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        String p = path.startsWith("/") ? path : "/" + path;

        // 检测重复路径并去重：
        // base = https://nas.shdj.cc.cd/dav, path = /dav/天翼家庭云/...
        // 提取 base 的 path 部分 = /dav，如果 path 以 /dav/ 开头，去重避免 /dav/dav/...
        String basePath = extractPath(b);
        if (basePath != null && basePath.length() > 1) {
            if (p.startsWith(basePath + "/")) {
                // 去掉 path 开头的 basePath，得到 /天翼家庭云/...
                String remaining = p.substring(basePath.length());
                return b + remaining;
            }
            if (p.equals(basePath)) {
                return b;
            }
        }

        return b + p;
    }

    /** 从 URL 提取 path 部分：https://nas.shdj.cc.cd/dav → /dav */
    private static String extractPath(String url) {
        int schemeEnd = url.indexOf("://");
        if (schemeEnd < 0) return null;
        int hostEnd = url.indexOf('/', schemeEnd + 3);
        if (hostEnd < 0) return null;
        return url.substring(hostEnd);
    }

    /** 解析 Location 头，支持绝对地址与相对地址（CF 一般返回绝对地址） */
    private static String resolveLocation(String baseUrl, String location) {
        if (location.startsWith("http://") || location.startsWith("https://")) {
            return location; // 绝对地址，直接用
        }
        // 相对地址：取 baseUrl 的 scheme://host[:port] 前缀拼接
        int schemeEnd = baseUrl.indexOf("://");
        if (schemeEnd < 0) return location;
        int hostEnd = baseUrl.indexOf('/', schemeEnd + 3);
        String origin = hostEnd < 0 ? baseUrl : baseUrl.substring(0, hostEnd);
        String rel = location.startsWith("/") ? location : "/" + location;
        return origin + rel;
    }

    /** Hop-by-Hop 头：逐跳首部，必须由各层自行管理，不能原样转发 */
    private static boolean isHopByHop(String name) {
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

    /**
     * 把请求体缓存为可重复读载体：小体入内存，大体落盘。
     */
    private BufferedBody bufferBody(InputStream in, long contentLength) throws IOException {
        // 注意：Content-Type 不在这里处理，由 buildRequest 通过复制请求头透传给上游。
        // RequestBody 的 contentType 传 null，OkHttp 就不会覆盖已复制的 Content-Type 头。
        if (contentLength >= 0 && contentLength <= MEMORY_BUFFER_LIMIT) {
            byte[] buf = new byte[(int) contentLength];
            int off = 0;
            while (off < buf.length) {
                int n = in.read(buf, off, buf.length - off);
                if (n < 0) break;
                off += n;
            }
            return new MemoryBody(buf);
        }

        // 未知长度或大体 → 边读边写入临时文件，先攒到内存，超阈值再落盘，避免小体也写文件
        ByteArrayOutputStream mem = new ByteArrayOutputStream();
        File file = null;
        FileOutputStream fos = null;
        byte[] tmp = new byte[16 * 1024];
        int n;
        long total = 0;
        while ((n = in.read(tmp)) > 0) {
            if (fos == null) {
                if (total + n <= MEMORY_BUFFER_LIMIT) {
                    mem.write(tmp, 0, n);
                    total += n;
                    continue;
                }
                // 超过阈值，把已攒内存数据落盘，后续继续写文件
                file = File.createTempFile("wgate_body_", ".bin");
                fos = new FileOutputStream(file);
                mem.writeTo(fos);
                mem = null;
            }
            fos.write(tmp, 0, n);
            total += n;
        }
        if (fos != null) {
            fos.flush();
            fos.close();
            return new FileBody(file);
        }
        // 整个体都没超过阈值（contentLength 未知但实际很小的情况）
        return new MemoryBody(mem.toByteArray());
    }

    // ------------------------------------------------------------------
    // 可重复读的请求体载体
    // ------------------------------------------------------------------

    abstract static class BufferedBody {
        abstract RequestBody toRequestBody();
    }

    /** 内存载体：每次 toRequestBody 都基于同一个 byte[] 重新构造，天然可重发 */
    static class MemoryBody extends BufferedBody {
        final byte[] data;

        MemoryBody(byte[] data) {
            this.data = data;
        }

        @Override
        RequestBody toRequestBody() {
            // contentType 传 null，沿用已复制的 Content-Type 头
            return RequestBody.create(null, data);
        }
    }

    /** 文件载体：每次 toRequestBody 基于 file 重新构造，OkHttp 按需读取，天然可重发 */
    static class FileBody extends BufferedBody {
        final File file;

        FileBody(File file) {
            this.file = file;
        }

        @Override
        RequestBody toRequestBody() {
            // contentType 传 null，沿用已复制的 Content-Type 头
            return RequestBody.create(null, file);
        }
    }
}
