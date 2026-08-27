package com.webdavgate.store;

import android.content.Context;
import android.content.SharedPreferences;

import com.webdavgate.model.GatewayNode;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 节点列表持久化：用 SharedPreferences 存一个 JSON 数组字符串。
 *
 * <p>选 SharedPreferences 而非 SQLite/Room：节点数量很少（通常 1~5 个），
 * 结构扁平、无关联查询，SP 全量读写足够且零 schema 迁移成本。
 *
 * <p>并发：SP 的 apply/commit 自带原子性，且本应用节点编辑发生在 UI 线程，
 * 多线程并发写并非场景。这里不做额外同步。
 */
public class NodeStore {

    private static final String PREF_NAME = "webdavgate_nodes";
    private static final String KEY_NODES = "nodes_json";

    private final SharedPreferences mPrefs;

    public NodeStore(Context context) {
        // 用 applicationContext 避免持有 Activity 导致泄漏
        mPrefs = context.getApplicationContext()
                .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    /** 加载全部节点。首次使用时返回空列表（不抛异常）。 */
    public List<GatewayNode> loadAll() {
        String json = mPrefs.getString(KEY_NODES, null);
        if (json == null || json.isEmpty()) {
            return new ArrayList<>();
        }
        List<GatewayNode> list = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                list.add(GatewayNode.fromJson(o));
            }
        } catch (JSONException e) {
            // 数据损坏时返回空表，避免启动崩溃；用户可在 UI 重新配置
            return new ArrayList<>();
        }
        return list;
    }

    /** 全量保存节点列表（增删改后调用）。 */
    public void saveAll(List<GatewayNode> nodes) {
        JSONArray arr = new JSONArray();
        for (GatewayNode n : nodes) {
            arr.put(n.toJson());
        }
        // commit 同步落盘，保证编辑后立即生效；节点量小，开销可忽略
        mPrefs.edit().putString(KEY_NODES, arr.toString()).commit();
    }
}
