package com.webdavgate.model;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.UUID;

/**
 * 网关节点模型：一个节点 = 一条 CF 302 入口 + 一个本地监听端口。
 *
 * <p>第二步扩展为可持久化的多节点列表：每个节点带稳定 id（增删改定位）、
 * enabled 标志（决定是否随前台服务一同启动），并提供 JSON 序列化供
 * {@link com.webdavgate.store.NodeStore} 落盘到 SharedPreferences。
 */
public class GatewayNode {

    /** 稳定唯一标识，增删改时定位用，新生成节点用 UUID */
    private final String id;

    /** 节点名称（仅用于展示，如 "家庭 NAS"） */
    private String name;

    /** Cloudflare 302 入口地址，CX 请求的 path 会被拼到它后面 */
    private String cfUrl;

    /** 本地监听端口（如 8888），CX 文件管理器连接 http://127.0.0.1:该端口 */
    private int localPort;

    /** 是否随前台服务启动；关闭的节点不会被 startAll 拉起，但可单独手动启动 */
    private boolean enabled;

    public GatewayNode(String name, String cfUrl, int localPort) {
        this(UUID.randomUUID().toString(), name, cfUrl, localPort, true);
    }

    public GatewayNode(String id, String name, String cfUrl, int localPort, boolean enabled) {
        this.id = id;
        this.name = name;
        this.cfUrl = cfUrl;
        this.localPort = localPort;
        this.enabled = enabled;
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

    /** 序列化为 JSON，供 NodeStore 落盘 */
    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("id", id);
            o.put("name", name == null ? "" : name);
            o.put("cfUrl", cfUrl == null ? "" : cfUrl);
            o.put("localPort", localPort);
            o.put("enabled", enabled);
        } catch (JSONException ignored) {
            // JSONObject.put 只在传入 NaN/Infinity 时抛，这里不会
        }
        return o;
    }

    /** 从 JSON 反序列化 */
    public static GatewayNode fromJson(JSONObject o) throws JSONException {
        return new GatewayNode(
                o.getString("id"),
                o.optString("name", ""),
                o.optString("cfUrl", ""),
                o.optInt("localPort", 0),
                o.optBoolean("enabled", true));
    }
}
