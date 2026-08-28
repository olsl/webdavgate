package com.webdavgate.core;

import com.webdavgate.log.LogStore;

import java.net.InetAddress;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * DNS TXT 记录解析器：通过 DNS 查询或 HTTP 查询获取 STUN 直连地址。
 *
 * <p>支持两种解析方式：
 * <ul>
 *   <li>HTTP 查询：向 CF 入口发请求，从 302/307 Location 头提取 STUN 地址（原有逻辑）</li>
 *   <li>DNS TXT 查询：直接查询域名的 TXT 记录，解析出 IP:Port</li>
 * </ul>
 *
 * <p>TXT 记录格式支持：
 * <pre>
 *   stun=http://1.2.3.4:5678
 *   ip=1.2.3.4&port=5678
 *   1.2.3.4:5678
 * </pre>
 */
public class DnsTxtResolver {

    private static final String TAG = "DnsTxtResolver";

    /** DNS 查询超时（秒） */
    private static final int DNS_TIMEOUT_SEC = 5;

    /** TXT 记录缓存有效期（毫秒） */
    private static final long CACHE_TTL_MS = 2 * 60 * 1000; // 2 分钟

    /** HTTP 客户端用于探测 CF 重定向 */
    private final OkHttpClient mClient;

    /** 缓存：domain → [origin, timestamp] */
    private final ConcurrentHashMap<String, String[]> mCache = new ConcurrentHashMap<>();

    public DnsTxtResolver() {
        this.mClient = new OkHttpClient.Builder()
                .followRedirects(false)
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .writeTimeout(5, TimeUnit.SECONDS)
                .build();
    }

    /**
     * 通过 CF 302/307 重定向获取 STUN 地址
     * （原有逻辑，作为 TXT 模式的回退）
     *
     * @param cfUrl Cloudflare 入口地址
     * @return STUN origin（如 http://1.2.3.4:5678），失败返回 null
     */
    public String resolveViaRedirect(String cfUrl) {
        // 检查缓存
        String[] cached = getCached(cfUrl);
        if (cached != null) {
            LogStore.d(TAG, "Redirect cache hit: " + cfUrl + " → " + cached[0]);
            return cached[0];
        }

        try {
            Request request = new Request.Builder()
                    .url(cfUrl)
                    .header("User-Agent", "WebDavGate/1.0")
                    .header("Accept", "*/*")
                    .build();

            try (Response response = mClient.newCall(request).execute()) {
                int code = response.code();
                if (code == 302 || code == 307 || code == 301 || code == 303 || code == 308) {
                    String location = response.header("Location");
                    if (location != null && (location.startsWith("http://") || location.startsWith("https://"))) {
                        String origin = extractOrigin(location);
                        if (origin != null) {
                            putCache(cfUrl, origin);
                            LogStore.i(TAG, "Redirect resolved: " + cfUrl + " → " + origin);
                            return origin;
                        }
                    }
                }
                LogStore.w(TAG, "Redirect fallback got " + code + ", no usable Location");
                return null;
            }
        } catch (Exception e) {
            LogStore.w(TAG, "Redirect resolve failed: " + e.getMessage());
            return null;
        }
    }

    /**
     * 通过 DNS TXT 记录直接获取 STUN 地址
     *
     * @param domain 要查询 TXT 的域名（如 "nas.shdj.cc.cd"）
     * @return STUN origin，失败返回 null
     */
    public String resolveViaTxt(String domain) {
        // 检查缓存
        String[] cached = getCached("txt:" + domain);
        if (cached != null) {
            LogStore.d(TAG, "TXT cache hit: " + domain + " → " + cached[0]);
            return cached[0];
        }

        // 通过 DNS over HTTPS 查询（稳定可靠）
        String result = resolveViaDoH(domain);

        if (result != null) {
            putCache("txt:" + domain, result);
            LogStore.i(TAG, "TXT resolved: " + domain + " → " + result);
        }
        return result;
    }

    /**
     * 清除缓存
     */
    public void invalidateCache(String domain) {
        mCache.remove(domain);
        mCache.remove("txt:" + domain);
    }

    // ------------------------------------------------------------------
    // 内部实现
    // ------------------------------------------------------------------

    /** 通过 DNS over HTTPS 查询 TXT（多 DoH 源依次尝试：阿里 → 腾讯 → Google） */
    private String resolveViaDoH(String domain) {
        // 国内可用的 DoH 源优先，dns.google 作为兜底（部分地区无法直连 8.8.8.8）
        String[] endpoints = {
                "https://dns.alidns.com/resolve?name=" + domain + "&type=TXT",
                "https://doh.pub/dns-query?name=" + domain + "&type=TXT",
                "https://dns.google/resolve?name=" + domain + "&type=TXT"
        };
        for (String url : endpoints) {
            String result = queryDoHEndpoint(url);
            if (result != null) {
                LogStore.d(TAG, "DoH success via: " + url);
                return result;
            }
        }
        return null;
    }

    /** 查询单个 DoH 端点，解析 JSON 响应中的 TXT 记录 */
    private String queryDoHEndpoint(String url) {
        try {
            Request request = new Request.Builder()
                    .url(url)
                    .header("Accept", "application/dns-json")
                    .build();

            try (Response response = mClient.newCall(request).execute()) {
                if (response.isSuccessful() && response.body() != null) {
                    String body = response.body().string();
                    // 用 JSONObject 解析
                    org.json.JSONObject json = new org.json.JSONObject(body);
                    org.json.JSONArray answers = json.optJSONArray("Answer");
                    if (answers != null && answers.length() > 0) {
                        // 合并所有 Answer 中的 TXT 数据
                        StringBuilder allTxt = new StringBuilder();
                        for (int i = 0; i < answers.length(); i++) {
                            org.json.JSONObject ans = answers.getJSONObject(i);
                            // data 可能是字符串或数组
                            org.json.JSONArray dataArr = ans.optJSONArray("data");
                            if (dataArr != null) {
                                for (int j = 0; j < dataArr.length(); j++) {
                                    allTxt.append(dataArr.getString(j));
                                }
                            } else {
                                String dataStr = ans.optString("data", "");
                                allTxt.append(dataStr);
                            }
                        }
                        String parsed = parseTxtRecord(allTxt.toString());
                        if (parsed != null) {
                            return parsed;
                        }
                    }
                }
            }
            return null;
        } catch (Exception e) {
            LogStore.w(TAG, "DoH endpoint failed: " + url + " → " + e.getMessage());
            return null;
        }
    }

    /**
     * 解析 TXT 记录内容，支持多种格式：
     * <pre>
     *   stun=http://1.2.3.4:5678
     *   ip=1.2.3.4&port=5678
     *   1.2.3.4:5678
     *   http://1.2.3.4:5678
     * </pre>
     */
    private String parseTxtRecord(String txt) {
        if (txt == null || txt.isEmpty()) return null;

        String trimmed = txt.trim();
        // 去除 DoH 可能添加的引号
        if (trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            trimmed = trimmed.substring(1, trimmed.length() - 1);
        }
        trimmed = trimmed.trim();
        if (trimmed.isEmpty()) return null;

        // 格式1: stun=http://... 或 stun=https://...
        int stunIdx = trimmed.indexOf("stun=");
        if (stunIdx >= 0) {
            String value = trimmed.substring(stunIdx + 5);
            int ampIdx = value.indexOf('&');
            if (ampIdx > 0) value = value.substring(0, ampIdx);
            if (value.startsWith("http://") || value.startsWith("https://")) {
                return extractOrigin(value);
            }
            return "http://" + value;
        }

        // 格式2: ip=...&port=...
        int ipIdx = trimmed.indexOf("ip=");
        int portIdx = trimmed.indexOf("port=");
        if (ipIdx >= 0 && portIdx >= 0) {
            String ip = trimmed.substring(ipIdx + 3, trimmed.indexOf('&', ipIdx));
            String port = trimmed.substring(portIdx + 5);
            int portEnd = port.indexOf('&');
            if (portEnd > 0) port = port.substring(0, portEnd);
            return "http://" + ip + ":" + port;
        }

        // 格式3: 纯 IP:Port
        if (trimmed.matches("^\\d+\\.\\d+\\.\\d+\\.\\d+:\\d+$")) {
            return "http://" + trimmed;
        }

        // 格式4: 带 scheme 的完整 URL
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            return extractOrigin(trimmed);
        }

        // 格式5: 纯 host:port (DNS 名称)
        int colonIdx = trimmed.indexOf(':');
        if (colonIdx > 0) {
            String host = trimmed.substring(0, colonIdx);
            try {
                // 尝试解析 host
                InetAddress addr = InetAddress.getByName(host);
                return "http://" + addr.getHostAddress() + trimmed.substring(colonIdx);
            } catch (Exception ignored) {
                // 可能已经是 IP，直接返回
                return "http://" + trimmed;
            }
        }

        return null;
    }

    /** 从 URL 提取 origin（scheme://host[:port]） */
    private static String extractOrigin(String url) {
        int schemeEnd = url.indexOf("://");
        if (schemeEnd < 0) return null;
        int hostEnd = url.indexOf('/', schemeEnd + 3);
        return hostEnd < 0 ? url : url.substring(0, hostEnd);
    }

    /** 获取缓存值 */
    private String[] getCached(String key) {
        String[] val = mCache.get(key);
        if (val == null) return null;
        long ts = Long.parseLong(val[1]);
        if (System.currentTimeMillis() - ts > CACHE_TTL_MS) {
            mCache.remove(key);
            return null;
        }
        return val;
    }

    /** 写入缓存 */
    private void putCache(String key, String origin) {
        mCache.put(key, new String[]{origin, String.valueOf(System.currentTimeMillis())});
    }
}
