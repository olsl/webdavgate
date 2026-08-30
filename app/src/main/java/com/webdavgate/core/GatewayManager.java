package com.webdavgate.core;

import android.content.Context;
import android.text.TextUtils;

import com.webdavgate.log.LogStore;
import com.webdavgate.model.GatewayNode;
import com.webdavgate.store.NodeStore;
import com.webdavgate.tcp.TcpTunnelHandler;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 多节点路由管理器：为每个 {@link GatewayNode} 维护一套运行时
 * （{@link RedirectForwarder} + {@link WebDavProxyHandler} + {@link GatewayServer}）。
 *
 * <p>职责：
 * <ul>
 *   <li>持有 {@link NodeStore} 加载/保存节点配置；</li>
 *   <li>{@link #startAll()} 启动所有 enabled 节点，{@link #stopAll()} 全部停止；</li>
 *   <li>{@link #startNode(String)}/{@link #stopNode(String)} 单节点启停（绑定失败上报错误而不抛）；</li>
 *   <li>通过 {@link Listener} 回调状态变化，UI 与前台服务据此刷新。</li>
 * </ul>
 *
 * <p>线程模型：所有 server.startup() 都在后台线程执行（bind 不能阻塞 UI），
 * 但状态字段用 volatile + 在回调时已确定线程，UI 回调需自行切主线程。
 */
public class GatewayManager {

    private static final String TAG = "GatewayManager";

    /** 单节点运行时：一个节点对应一组转发器/处理器/服务器。
     *  TCP 透传模式与 HTTP 反向代理模式二选一，故两者均可为 null。 */
    private static class Runtime {
        final RedirectForwarder forwarder;
        final DnsTxtResolver txtResolver;
        final WebDavProxyHandler httpHandler;
        /** TCP 透传模式处理器（与 HTTP 服务器二选一，可为 null） */
        final TcpTunnelHandler tcpHandler;
        /** HTTP 反向代理模式的本地服务器（可为 null） */
        final GatewayServer server;

        Runtime(RedirectForwarder f, DnsTxtResolver t, WebDavProxyHandler h, TcpTunnelHandler tcp, GatewayServer s) {
            this.forwarder = f;
            this.txtResolver = t;
            this.httpHandler = h;
            this.tcpHandler = tcp;
            this.server = s;
        }

        /** 节点是否在运行：TCP 模式看隧道，HTTP 模式看服务器 */
        boolean isRunning() {
            if (tcpHandler != null) return tcpHandler.isRunning();
            return server != null && server.isRunning();
        }

        /** 停止该节点的全部监听资源 */
        void shutdown() {
            if (tcpHandler != null) tcpHandler.shutdown();
            if (server != null) server.shutdown();
        }
    }

    /** 状态变化回调接口，UI/Service 实现 */
    public interface Listener {
        /** 某节点或整体状态变化（启停、错误） */
        void onStateChanged();
    }

    private final Context mAppContext;
    private final NodeStore mStore;
    private final List<GatewayNode> mNodes = new ArrayList<>();
    // 用并发 Map：后台启停线程 put/clear 与通知/读状态线程 read 可能并发
    private final Map<String, Runtime> mRuntimes = new ConcurrentHashMap<>();
    private final Map<String, String> mErrors = new ConcurrentHashMap<>();
    // 读多写少且遍历发生在后台回调线程，用 COW 避免并发修改异常
    private final List<Listener> mListeners = new CopyOnWriteArrayList<>();

    private volatile boolean mActive; // 服务整体是否处于"已启动"态

    public GatewayManager(Context context) {
        mAppContext = context.getApplicationContext();
        mStore = new NodeStore(mAppContext);
        reload();
    }

    /** 从磁盘重新加载节点配置（不改变已运行实例） */
    public void reload() {
        List<GatewayNode> loaded = mStore.loadAll();
        synchronized (mNodes) {
            mNodes.clear();
            mNodes.addAll(loaded);
        }
        // 首次使用或全部节点被删除时，不自动创建默认节点
        if (mNodes.isEmpty()) {
            LogStore.i(TAG, "No nodes configured, waiting for user setup");
        }
    }

    public void addListener(Listener l) {
        if (!mListeners.contains(l)) mListeners.add(l);
    }

    public void removeListener(Listener l) {
        mListeners.remove(l);
    }

    private void notifyStateChanged() {
        for (Listener l : mListeners) {
            l.onStateChanged();
        }
    }

    // ------------------------------------------------------------------
    // 节点配置 CRUD（UI 线程调用，落盘 + 回调）
    // ------------------------------------------------------------------

    public List<GatewayNode> getNodes() {
        synchronized (mNodes) {
            return new ArrayList<>(mNodes);
        }
    }

    public GatewayNode getNode(String id) {
        synchronized (mNodes) {
            for (GatewayNode n : mNodes) {
                if (n.getId().equals(id)) return n;
            }
        }
        return null;
    }

    /** 新增节点；端口冲突由调用方在 UI 校验，这里只做非空/范围基本检查 */
    public boolean addNode(GatewayNode node) {
        if (node == null || node.getLocalPort() <= 0) {
            return false;
        }
        // REDIRECT 模式必须有 cfUrl，TXT/AUTO 模式必须有 stunDomain
        boolean modeOk;
        if (node.getDiscoveryMethod() == GatewayNode.DISCOVERY_REDIRECT) {
            modeOk = !TextUtils.isEmpty(node.getCfUrl());
        } else {
            modeOk = !TextUtils.isEmpty(node.getStunDomain());
        }
        if (!modeOk) return false;

        synchronized (mNodes) {
            mNodes.add(node);
        }
        mStore.saveAll(getNodes());
        notifyStateChanged();
        return true;
    }

    public boolean updateNode(GatewayNode node) {
        if (node == null) return false;
        synchronized (mNodes) {
            for (int i = 0; i < mNodes.size(); i++) {
                if (mNodes.get(i).getId().equals(node.getId())) {
                    mNodes.set(i, node);
                    break;
                }
            }
        }
        mStore.saveAll(getNodes());
        // 若该节点正在运行，配置已变（端口/URL），需重启以生效
        if (isRunning(node.getId())) {
            stopNode(node.getId());
            if (node.isEnabled() && mActive) {
                startNode(node.getId());
            }
        }
        notifyStateChanged();
        return true;
    }

    public boolean removeNode(String id) {
        // 先停再删，释放端口
        stopNode(id);
        boolean removed;
        synchronized (mNodes) {
            removed = mNodes.removeIf(n -> n.getId().equals(id));
        }
        if (removed) {
            mStore.saveAll(getNodes());
            mErrors.remove(id);
            notifyStateChanged();
        }
        return removed;
    }

    /** 切换 enabled 标志；若服务在跑则即时启停该节点 */
    public void setEnabled(String id, boolean enabled) {
        GatewayNode n = getNode(id);
        if (n == null) return;
        n.setEnabled(enabled);
        mStore.saveAll(getNodes());
        if (mActive) {
            if (enabled) startNode(id);
            else stopNode(id);
        }
        notifyStateChanged();
    }

    // ------------------------------------------------------------------
    // 启停控制（后台线程执行 bind）
    // ------------------------------------------------------------------

    public boolean isActive() {
        return mActive;
    }

    public int getRunningCount() {
        int c = 0;
        for (Runtime r : mRuntimes.values()) {
            if (r.isRunning()) c++;
        }
        return c;
    }

    public boolean isRunning(String id) {
        Runtime r = mRuntimes.get(id);
        return r != null && r.isRunning();
    }

    public String getError(String id) {
        return mErrors.get(id);
    }

    /** 启动服务：拉起所有 enabled 节点。在后台线程执行，状态变化时回调。 */
    public void startAll() {
        mActive = true;
        List<GatewayNode> snapshot;
        synchronized (mNodes) {
            snapshot = new ArrayList<>(mNodes);
        }
        int toStart = 0;
        for (GatewayNode n : snapshot) {
            if (n.isEnabled()) toStart++;
        }
        LogStore.i(TAG, "startAll: total=" + snapshot.size() + ", toStart=" + toStart);
        new Thread(() -> {
            for (GatewayNode n : snapshot) {
                if (n.isEnabled()) {
                    startNodeInternal(n);
                }
            }
            LogStore.i(TAG, "startAll: done, running=" + getRunningCount());
            notifyStateChanged();
        }, "Gate-StartAll").start();
    }

    /** 停止服务：停掉全部运行实例 */
    public void stopAll() {
        mActive = false;
        new Thread(() -> {
            List<Runtime> rs;
            synchronized (mRuntimes) {
                rs = new ArrayList<>(mRuntimes.values());
                mRuntimes.clear();
            }
            for (Runtime r : rs) {
                r.shutdown();
            }
            notifyStateChanged();
        }, "Gate-StopAll").start();
    }

    public void startNode(String id) {
        GatewayNode n = getNode(id);
        if (n == null) return;
        new Thread(() -> {
            startNodeInternal(n);
            notifyStateChanged();
        }, "Gate-Start-" + id).start();
    }

    public void stopNode(String id) {
        new Thread(() -> {
            Runtime r;
            synchronized (mRuntimes) {
                r = mRuntimes.remove(id);
            }
            if (r != null) r.shutdown();
            notifyStateChanged();
        }, "Gate-Stop-" + id).start();
    }

    private void startNodeInternal(GatewayNode n) {
        // 已在跑则跳过
        Runtime existing = mRuntimes.get(n.getId());
        if (existing != null && existing.isRunning()) {
            LogStore.d(TAG, "Node already running: " + n.getName() + " (port " + n.getLocalPort() + ")");
            return;
        }
        try {
            // 根据模式确定传给 RedirectForwarder 的基础 URL
            // REDIRECT: 使用 cfUrl
            // TXT/AUTO: 使用 stunDomain 作为基础 URL（RedirectForwarder 只用它做缓存 key 和日志）
            String baseUrl;
            if (n.getDiscoveryMethod() == GatewayNode.DISCOVERY_REDIRECT) {
                baseUrl = n.getCfUrl();
            } else {
                baseUrl = "https://" + n.getStunDomain();
            }
            // TCP 透传模式：本地端口直接做四层字节流管道，不再启动 HTTP 服务器（避免同端口冲突）
            if (n.getDiscoveryMethod() == GatewayNode.DISCOVERY_TXT
                    || n.getDiscoveryMethod() == GatewayNode.DISCOVERY_REDIRECT) {
                TcpTunnelHandler tcpHandler = new TcpTunnelHandler(
                        n.getLocalPort(), 8888, n.getDiscoveryMethod(),
                        n.getCfUrl(), n.getStunDomain());
                // startServer() 是阻塞式 accept 循环，必须运行在独立线程，否则会卡死启动流程
                new Thread(tcpHandler::startServer, "TcpTunnel-" + n.getId()).start();
                synchronized (mRuntimes) {
                    mRuntimes.put(n.getId(), new Runtime(null, null, null, tcpHandler, null));
                }
                mErrors.remove(n.getId());
                LogStore.i(TAG, "Node started (TCP tunnel): " + n.getName()
                        + " (port " + n.getLocalPort()
                        + ", mode=" + getModeLabel(n.getDiscoveryMethod()) + ")");
                return;
            }

            RedirectForwarder forwarder = new RedirectForwarder(baseUrl);
            WebDavProxyHandler httpHandler = new WebDavProxyHandler(forwarder, n);
            DnsTxtResolver txtResolver = new DnsTxtResolver();
            GatewayServer httpServer = new GatewayServer(n.getLocalPort(), httpHandler);
            httpServer.startup();
            synchronized (mRuntimes) {
                mRuntimes.put(n.getId(), new Runtime(forwarder, txtResolver, httpHandler, null, httpServer));
            }
            mErrors.remove(n.getId());
            String mode = getModeLabel(n.getDiscoveryMethod());
            String address = n.getDiscoveryMethod() == GatewayNode.DISCOVERY_REDIRECT
                    ? n.getCfUrl() : n.getStunDomain();
            LogStore.i(TAG, "Node started: " + n.getName() + " (port " + httpServer.getBoundPort() + ", addr=" + address + ", mode=" + mode + ")");
        } catch (Exception e) {
            // 端口占用/无权限等：记录错误，不影响其他节点启动
            String errMsg = e.getMessage();
            mErrors.put(n.getId(), errMsg);
            LogStore.e(TAG, "Node failed to start: " + n.getName() + " port=" + n.getLocalPort() + " -> " + errMsg, e);
        }
    }

    private String getModeLabel(int method) {
        switch (method) {
            case GatewayNode.DISCOVERY_TXT: return "TXT";
            case GatewayNode.DISCOVERY_AUTO: return "AUTO";
            default: return "302";
        }
    }
}
