package com.webdavgate.tcp;

import com.webdavgate.core.DnsTxtResolver;
import com.webdavgate.log.LogStore;
import com.webdavgate.model.GatewayNode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * TCP 透传处理器（TCP Tunneling）：
 * <ul>
 *   <li>纯 TCP 字节流搬运，不解析也不拦截 HTTP 协议</li>
 *   <li>支持 TXT 和 302 两种独立寻址方式，互不回退</li>
 *   <li>仅对客户端首段请求头中的 Host 行改写为配置域名，确保 Lucky 按域名路由
 *       （不注入 CONNECT 等任何前缀，避免破坏纯 HTTP 反代的请求流）</li>
 *   <li>全双工双向数据透传（两条独立管道线程）</li>
 * </ul>
 */
public class TcpTunnelHandler {

    private static final String TAG = "TcpTunnelHandler";

    /** 请求头缓冲上限：超过仍未见 \r\n\r\n 则视为非 HTTP 流，原样转发 */
    private static final int MAX_HEAD_SIZE = 16 * 1024;

    private final int mLocalPort;
    private final int mRemotePort;
    private final AtomicInteger mActiveConnections;

    /** 发现模式：0=302 重定向，1=TXT 记录（与 GatewayNode.DISCOVERY_* 常量一致） */
    private final int mDiscoveryMethod;

    /** Cloudflare 302 入口地址（302 模式寻址用，如 "https://nas.shdj.cc.cd"） */
    private final String mCfUrl;

    /** TXT 记录查询域名（TXT 模式寻址用，如 "nas.shdj.cc.cd"） */
    private final String mStunDomain;

    /** 改写 Host 行所用的域名：TXT 模式用 stunDomain，302 模式动态取跳转目标的 host */
    private volatile String mHostHeader;

    /** 地址解析器：内部带缓存（TTL 10 分钟），跨连接复用，避免每个连接重复寻址 */
    private final DnsTxtResolver mResolver;

    /** 本地监听的 ServerSocket，shutdown 时关闭以退出 accept 循环 */
    private volatile ServerSocket mServerSocket;
    /** TCP 服务器是否处于运行态 */
    private volatile boolean mRunning;

    public TcpTunnelHandler(int localPort, int remotePort, int discoveryMethod,
                            String cfUrl, String stunDomain) {
        this.mLocalPort = localPort;
        this.mRemotePort = remotePort;
        this.mDiscoveryMethod = discoveryMethod;
        this.mCfUrl = cfUrl;
        this.mStunDomain = stunDomain;
        // Host 来源：Lucky 按域名路由，必须用用户配置的域名而非 STUN 直连 IP
        if (discoveryMethod == GatewayNode.DISCOVERY_TXT) {
            this.mHostHeader = stunDomain;
        } else {
            this.mHostHeader = hostOf(cfUrl);
        }
        this.mActiveConnections = new AtomicInteger(0);
        this.mResolver = new DnsTxtResolver();
    }

    /**
     * 解析目标地址（TXT 与 302 两种独立方式，互不回退）：
     * <ul>
     *   <li>TXT 模式：复用 {@link DnsTxtResolver#resolveViaTxt(String)} 查询 stunDomain 的 TXT 记录</li>
     *   <li>302 模式：复用 {@link DnsTxtResolver#resolveViaRedirect(String)} 请求 cfUrl，
     *       从重定向响应的 Location 头提取真实地址</li>
     * </ul>
     *
     * @return 远端 Lucky 的 socket 地址
     */
    private InetSocketAddress resolveDestination() throws Exception {
        String origin;
        if (mDiscoveryMethod == GatewayNode.DISCOVERY_TXT) {
            // TXT 模式：直接查询 DNS TXT 记录获取 STUN 地址（如 http://1.2.3.4:5678）
            if (mStunDomain == null || mStunDomain.isEmpty()) {
                throw new RuntimeException("TXT mode requires stunDomain but it is empty");
            }
            origin = mResolver.resolveViaTxt(mStunDomain);
            LogStore.i(TAG, "TXT resolved: " + mStunDomain + " → " + origin);
        } else {
            // 302 模式：向 CF 入口发起 GET，解析 302/307 Location 头获取真实地址
            if (mCfUrl == null || mCfUrl.isEmpty()) {
                throw new RuntimeException("302 mode requires cfUrl but it is empty");
            }
            origin = mResolver.resolveViaRedirect(mCfUrl);
            LogStore.i(TAG, "Redirect resolved: " + mCfUrl + " → " + origin);
        }
        if (origin == null) {
            throw new RuntimeException("Address resolution failed (mode=" + mDiscoveryMethod + ")");
        }
        // 302 模式 Host 动态取跳转目标的域名/主机，而不是卡片上填的入口域名
        if (mDiscoveryMethod != GatewayNode.DISCOVERY_TXT) {
            String redirectHost = hostOf(origin);
            if (redirectHost != null && !redirectHost.isEmpty()) {
                if (!redirectHost.equals(mHostHeader)) {
                    LogStore.i(TAG, "Host header updated from redirect target: " + redirectHost);
                }
                mHostHeader = redirectHost;
            }
        }
        return resolveHostPort(origin);
    }

    /**
     * 解析 origin 字符串为 socket 地址，兼容带 scheme 的格式
     * （如 "http://192.168.1.1:8888"）；未带端口时 http 默认 80、https 默认 443
     */
    private InetSocketAddress resolveHostPort(String origin) throws Exception {
        String hostPort = origin;
        int defaultPort = 80;
        int schemeEnd = hostPort.indexOf("://");
        if (schemeEnd >= 0) {
            if (hostPort.startsWith("https://")) defaultPort = 443;
            hostPort = hostPort.substring(schemeEnd + 3);
        }
        // 去掉可能残留的 path 部分
        int slashIdx = hostPort.indexOf('/');
        if (slashIdx >= 0) hostPort = hostPort.substring(0, slashIdx);

        int colonIdx = hostPort.lastIndexOf(':');
        if (colonIdx < 0) {
            return new InetSocketAddress(hostPort, defaultPort);
        }
        String host = hostPort.substring(0, colonIdx);
        int port = Integer.parseInt(hostPort.substring(colonIdx + 1));
        return new InetSocketAddress(host, port);
    }

    /** 从 URL 提取 host[:port]：https://nas.shdj.cc.cd/dav → nas.shdj.cc.cd */
    private static String hostOf(String url) {
        if (url == null) return null;
        String s = url;
        int schemeEnd = s.indexOf("://");
        if (schemeEnd >= 0) s = s.substring(schemeEnd + 3);
        int slashIdx = s.indexOf('/');
        if (slashIdx >= 0) s = s.substring(0, slashIdx);
        return s.isEmpty() ? null : s;
    }

    /**
     * 处理单个 TCP 连接（客户端请求）
     */
    private void handleClientConnection(Socket clientSocket) {
        mActiveConnections.incrementAndGet();
        LogStore.i(TAG, "Client connected, active=" + mActiveConnections.get());

        Socket remoteSocket = null;
        try {
            // 解析远端地址（根据配置使用 TXT 或 302 方式）
            InetSocketAddress remoteAddr = resolveDestination();
            LogStore.i(TAG, "Resolved destination: " + remoteAddr);

            // 建立到远端的 TCP 连接
            remoteSocket = new Socket();
            remoteSocket.connect(remoteAddr, 10000);
            remoteSocket.setSoTimeout(0); // 连接建立后读阻塞不限时，兼容 keep-alive 长连接
            LogStore.i(TAG, "Connected to remote: " + remoteAddr);

            // 读取客户端首段数据，仅改写其中 Host 行为配置域名（不注入 CONNECT 前缀）
            byte[] head = readRequestHead(clientSocket);
            if (head.length >= 2 && (head[0] & 0xFF) == 0x16 && (head[1] & 0xFF) == 0x03) {
                LogStore.w(TAG, "客户端发送的是 TLS(https) 握手，本隧道后端为纯 HTTP，"
                        + "请在客户端改用 http:// 连接（如 CX 文件管理器中选择 HTTP/WebDAV 而非 HTTPS）");
                return;
            }
            byte[] outHead = rewriteHostLine(head);
            OutputStream remoteOut = remoteSocket.getOutputStream();
            remoteOut.write(outHead);
            remoteOut.flush();

            LogStore.i(TAG, "TCP tunnel established: "
                    + remoteAddr.getHostString() + ":" + remoteAddr.getPort());

            // 全双工透传：remote->local 独立线程；local->remote 在当前线程跑
            final Socket remote = remoteSocket;
            final Socket local = clientSocket;
            Thread r2l = new Thread(() -> pipe(remote, local, "remote->local"),
                    "TunnelR2L-" + mLocalPort);
            r2l.start();
            pipe(local, remote, "local->remote");
            r2l.join(5000);
        } catch (Exception e) {
            LogStore.e(TAG, "Connection error: " + e.getMessage(), e);
        } finally {
            try { clientSocket.close(); } catch (IOException ignored) { }
            if (remoteSocket != null) {
                try { remoteSocket.close(); } catch (IOException ignored) { }
            }
            mActiveConnections.decrementAndGet();
        }
    }

    /**
     * 读取客户端首段数据，缓冲至请求头结束符 \r\n\r\n（或达上限 / 流结束 / 读超时）。
     * 此阶段设置读超时，避免客户端只建连不发数据时永久阻塞；读完后恢复为不限时。
     */
    private byte[] readRequestHead(Socket clientSocket) {
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        try {
            InputStream in = clientSocket.getInputStream();
            clientSocket.setSoTimeout(10000);
            // 请求头结束符状态机：同时兼容 CRLF(\r\n\r\n) 与裸 LF(\n\n) 换行，
            // 部分 Android 客户端（如 Dalvik/CX 文件管理器）只发裸 \n
            // 0 初始 1=\r 2=\r\n 3=\r\n\r 4=完成 5=裸 \n
            int state = 0;
            int prev = -1;
            int b;
            while ((b = in.read()) != -1) {
                head.write(b);
                // TLS ClientHello（0x16 0x03）：客户端在用 https，非 HTTP 请求，
                // 不必再等请求头结束符，立即交由上层快速失败并给出提示
                if (prev == 0x16 && b == 0x03) break;
                prev = b;
                if (b == '\r') {
                    state = (state == 2) ? 3 : 1;
                } else if (b == '\n') {
                    if (state == 1) state = 2;        // \r\n
                    else if (state == 3) state = 4;   // \r\n\r\n
                    else if (state == 5) state = 4;   // \n\n
                    else state = 5;                   // 裸 \n
                } else {
                    state = 0;
                }
                if (state == 4 || head.size() >= MAX_HEAD_SIZE) break;
            }
        } catch (SocketTimeoutException e) {
            LogStore.w(TAG, "Read request head timeout, forward " + head.size() + " bytes as-is");
        } catch (IOException e) {
            LogStore.w(TAG, "Read request head failed: " + e.getMessage());
        } finally {
            try { clientSocket.setSoTimeout(0); } catch (IOException ignored) { }
        }
        return head.toByteArray();
    }

    /**
     * 将 HTTP 请求头中的 Host 行替换为配置域名，其余字节原样保留。
     * 非 HTTP 请求、未配置 Host、或配置为通配符域名（*. 开头）时原样转发。
     */
    private byte[] rewriteHostLine(byte[] head) {
        if (head.length == 0) return head;
        if (mHostHeader == null || mHostHeader.isEmpty()) return head;
        if (mHostHeader.startsWith("*.")) {
            LogStore.w(TAG, "Configured domain is wildcard, cannot use as Host, forward as-is: "
                    + mHostHeader);
            return head;
        }
        // ISO_8859_1 保证字节级无损往返
        String text = new String(head, StandardCharsets.ISO_8859_1);
        // 兼容 CRLF 与裸 LF 换行（部分 Android 客户端如 Dalvik 只发 \n），
        // 按客户端实际使用的换行符拆分并以同样的换行符重组，保持字节风格一致
        String delim = text.indexOf("\r\n") >= 0 ? "\r\n" : "\n";
        int lineEnd = text.indexOf(delim);
        if (lineEnd < 0) return head;
        String requestLine = text.substring(0, lineEnd);
        LogStore.d(TAG, "Client request line: " + requestLine);
        String[] parts = requestLine.split(" ");
        if (parts.length < 3 || !parts[2].startsWith("HTTP/")) {
            return head; // 非 HTTP 请求行，按纯 TCP 流原样转发
        }
        String[] lines = text.substring(lineEnd + delim.length()).split(delim, -1);
        String newHostLine = "Host: " + mHostHeader;
        List<String> out = new ArrayList<>();
        boolean replacedHost = false;
        boolean replacedConn = false;
        for (String line : lines) {
            String lower = line.toLowerCase(Locale.US);
            if (lower.startsWith("host:")) {
                if (!replacedHost) {
                    out.add(newHostLine);
                    replacedHost = true;
                }
                // 丢弃重复的 Host 行
            } else if (lower.startsWith("connection:")) {
                if (!replacedConn) {
                    out.add("Connection: close");
                    replacedConn = true;
                }
                // 丢弃重复的 Connection 行
            } else {
                out.add(line);
            }
        }
        if (!replacedHost) {
            // 客户端未带 Host 行：在请求行后插入
            out.add(0, newHostLine);
        }
        if (!replacedConn) {
            // 插到结尾空行（头部结束符）之前，避免落在请求体里
            int idx = out.size();
            while (idx > 0 && out.get(idx - 1).isEmpty()) idx--;
            out.add(idx, "Connection: close");
        }
        String result = requestLine + delim + joinLines(out, delim);
        LogStore.i(TAG, "Host line rewritten to: " + mHostHeader + ", forced Connection: close");
        return result.getBytes(StandardCharsets.ISO_8859_1);
    }

    /** 用指定换行符连接各行（minSdk 24 无 String.join） */
    private static String joinLines(List<String> lines, String delim) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) sb.append(delim);
            sb.append(lines.get(i));
        }
        return sb.toString();
    }

    private static String joinLines(String[] lines, String delim) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) sb.append(delim);
            sb.append(lines[i]);
        }
        return sb.toString();
    }

    /**
     * 单向管道：从 from 读取并原样写入 to，任一端 EOF/断开即退出；
     * EOF 时 shutdownOutput 通知对端数据结束（兼容需请求结束信号的 HTTP 语义）
     */
    private void pipe(Socket from, Socket to, String name) {
        try {
            InputStream in = from.getInputStream();
            OutputStream out = to.getOutputStream();
            byte[] buffer = new byte[8192];
            int count;
            while ((count = in.read(buffer)) != -1) {
                out.write(buffer, 0, count);
                out.flush();
            }
            LogStore.d(TAG, "Pipe " + name + " EOF");
            try { to.shutdownOutput(); } catch (IOException ignored) { }
        } catch (Exception e) {
            LogStore.d(TAG, "Pipe " + name + " ended: " + e.getMessage());
        }
    }

    /**
     * 启动 TCP 服务器，监听本地端口
     */
    public void startServer() {
        try {
            ServerSocket serverSocket = new ServerSocket();
            serverSocket.setReuseAddress(true);
            // 绑定 0.0.0.0：本机与局域网设备均可访问
            serverSocket.bind(new InetSocketAddress("0.0.0.0", mLocalPort));
            mServerSocket = serverSocket;
            mRunning = true;

            LogStore.i(TAG, "TCP server listening on 0.0.0.0:" + mLocalPort);

            // 处理每个连接
            while (true) {
                Socket clientSocket = serverSocket.accept();
                LogStore.i(TAG, "Client connected");
                new Thread(() -> {
                    try {
                        handleClientConnection(clientSocket);
                    } catch (Exception e) {
                        LogStore.e(TAG, "Connection error: " + e.getMessage(), e);
                    }
                }, "TcpClient-" + mLocalPort).start();
            }
        } catch (Exception e) {
            // 主动 shutdown 时 accept 会抛 SocketException，属正常退出，不记错误日志
            if (mRunning) {
                LogStore.e(TAG, "TCP server failed to start: " + e.getMessage(), e);
            }
        } finally {
            mRunning = false;
        }
    }

    /**
     * TCP 服务器是否正在运行（供 GatewayManager 查询节点状态）
     */
    public boolean isRunning() {
        return mRunning;
    }

    /**
     * 获取当前活跃连接数
     */
    public int getActiveConnectionCount() {
        return mActiveConnections.get();
    }

    /**
     * 清理资源
     */
    public void shutdown() {
        mRunning = false;
        if (mServerSocket != null) {
            try { mServerSocket.close(); } catch (IOException ignored) { }
        }
        LogStore.i(TAG, "TCP server stopped on port " + mLocalPort);
    }
}
