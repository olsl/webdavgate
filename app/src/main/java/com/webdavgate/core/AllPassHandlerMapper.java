package com.webdavgate.core;

import org.apache.http.HttpRequest;
import org.apache.http.protocol.HttpRequestHandler;
import org.apache.http.protocol.HttpRequestHandlerMapper;

/**
 * 最简全通过路由表：任何请求都返回同一个 handler。
 *
 * <p>HttpCore 4.4 的 {@code UriHttpRequestHandlerMapper} 在某些请求形态下
 * （尤其是 WebDAV 扩展方法 PROPFIND/MKCOL/COPY/MOVE 等）的路径解析/通配符匹配
 * 会返回 null，导致 {@link org.apache.http.protocol.HttpService#doService}
 * 直接回 501 Not Implemented，根本到不了我们的 handler。
 *
 * <p>本实现放弃所有模式匹配，直接把"全路径、全方法"的请求都交给同一个 handler，
 * 从根本上绕开 HttpCore 4.4 的 handler mapper 缺陷。
 */
public class AllPassHandlerMapper implements HttpRequestHandlerMapper {

    private final HttpRequestHandler mHandler;

    public AllPassHandlerMapper(HttpRequestHandler handler) {
        mHandler = handler;
    }

    @Override
    public HttpRequestHandler lookup(HttpRequest request) {
        // 无论什么方法、什么路径，都返回同一个 handler
        return mHandler;
    }
}
