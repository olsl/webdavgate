package com.webdavgate.model;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.UUID;

/**
 * 网关节点模型：一个节点 = 一条 CF 入口 + 一个本地监听端口 + 发现模式。
 *
 * <p>支持两种 STUN 直连地址发现方式：
 *   <ul>
 *     <li>{@link #DISCOVERY_REDIRECT}：传统 Cloudflare 302/307 重定向解析（默认）</li>
 *     <li>{@link #DISCOVERY_TXT}：DNS TXT 记录解析，直接从 DNS 获取 STUN 地址</li>
 *     <li>{@link #DISCOVERY_AUTO}：先试 TXT，失败回退到重定向</li>
 *   </ul>
 *
 * <p>可持久化的多节点列表：每个节点带稳定 id（增删改定位）、
 * enabled 标志（决定是否随前台服务一同启动），并提供 JSON 序列化供
 * {@link com.webdavgate.store.NodeStore} 落盘到 SharedPreferences。
 */
public class GatewayNode {

    /** 发现模式：通过 Cloudflare 302/307 重定向解析 STUN 地址 */
    public static final int DISCOVERY_REDIRECT = 0;
    /** 发现模式：通过 DNS TXT 记录直接获取 STUN 地址 */
    public static final int DISCOVERY_TXT = 1;
    /** 发现模式：优先 TXT，失败回退到重定向 */
    public static final int DISCOVERY_AUTO = 2;

    /** 稳定唯一标识，增删改时定位用，新生成节点用 UUID */
    private final String id;

    /** 节点名称（仅用于展示，如 "家庭 NAS"） */
    private String name;

    /** Cloudflare 入口地址，CX 请求的 path 会被拼到它后面 */
    private String cfUrl;

    /** 本地监听端口（如 8888），CX 文件管理器连接 http://127.0.0.1:该端口 */
    private int localPort;

    /** 是否随前台服务启动；关闭的节点不会被 startAll 拉起，但可单独手动启动 */
    private boolean enabled;

    /** STUN 地址发现模式：0=重定向，1=TXT，2=自动 */
    private int discoveryMethod;

    /** TXT 记录查询的域名（如 "nas.shdj.cc.cd"），为空则用 cfUrl 的域名 */
    private String stunDomain;

    /** 本地 TCP Socket，用于 TCP 透传模式 */
    private java.net.Socket localSocket;

    public GatewayNode(String name, String cfUrl, int localPort) {
        this(UUID.randomUUID().toString(), name, cfUrl, localPort, true, DISCOVERY_REDIRECT, "");
    }

    public GatewayNode(String id, String name, String cfUrl, int localPort, boolean enabled) {
        this(id, name, cfUrl, localPort, enabled, DISCOVERY_REDIRECT, "");
    }

    public GatewayNode(String id, String name, String cfUrl, int localPort,
                       boolean enabled, int discoveryMethod, String stunDomain) {
        this.id = id;
        this.name = name;
        this.cfUrl = cfUrl;
        this.localPort = localPort;
        this.enabled = enabled;
        this.discoveryMethod = discoveryMethod;
        this.stunDomain = stunDomain == null ? "" : stunDomain;
    }

    public String getId() { return id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getCfUrl() { return cfUrl; }
    public void setCfUrl(String cfUrl) { this.cfUrl = cfUrl; }

    public int getLocalPort() { return localPort; }
    public void setLocalPort(int localPort) { this.localPort = localPort; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public int getDiscoveryMethod() { return discoveryMethod; }
    public void setDiscoveryMethod(int discoveryMethod) { this.discoveryMethod = discoveryMethod; }

    public String getStunDomain() { return stunDomain; }
    public void setStunDomain(String stunDomain) { this.stunDomain = stunDomain == null ? "" : stunDomain; }

    public java.net.Socket getLocalSocket() { return localSocket; }
    public void setLocalSocket(java.net.Socket socket) { this.localSocket = socket; }

    /** 序列化为 JSON，供 NodeStore 落盘 */
    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("id", id);
            o.put("name", name == null ? "" : name);
            o.put("cfUrl", cfUrl == null ? "" : cfUrl);
            o.put("localPort", localPort);
            o.put("enabled", enabled);
            o.put("discoveryMethod", discoveryMethod);
            o.put("stunDomain", stunDomain == null ? "" : stunDomain);
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
        return o;
    }

    /** 从 JSON 反序列化 */
    public static GatewayNode fromJson(JSONObject o) {
        try {
            return new GatewayNode(
                    o.getString("id"),
                    o.getString("name"),
                    o.getString("cfUrl"),
                    o.getInt("localPort"),
                    o.getBoolean("enabled"),
                    o.getInt("discoveryMethod"),
                    o.getString("stunDomain")
            );
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
    }
}
