package com.webdavgate.ui;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.card.MaterialCardView;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.webdavgate.R;
import com.webdavgate.core.GatewayManager;
import com.webdavgate.model.GatewayNode;

import java.util.ArrayList;
import java.util.List;

/**
 * 节点列表适配器。绑定数据来自 {@link GatewayManager}：
 * 节点配置来自 manager.getNodes()，运行状态/错误来自 manager 实时查询，
 * 这样启停后只需 notifyDataSetChanged（manager 状态已变）即可整表刷新，
 * 避免在每个 ViewHolder 维护异步状态。
 */
public class NodeAdapter extends RecyclerView.Adapter<NodeAdapter.VH> {

    /** 节点交互回调，由 MainActivity 实现 */
    public interface Callback {
        void onEdit(GatewayNode node);
        void onRemove(String id);
        void onToggleEnabled(String id, boolean enabled);
    }

    private final Callback mCb;
    private GatewayManager mManager;
    private List<GatewayNode> mNodes = new ArrayList<>();

    public NodeAdapter(GatewayManager manager, Callback cb) {
        mManager = manager;
        mCb = cb;
    }

    /** bind 完成后注入真实 manager，后续 onBindViewHolder 才能查到实时运行状态 */
    public void setManager(GatewayManager manager) {
        mManager = manager;
        notifyDataSetChanged();
    }

    public void setNodes(List<GatewayNode> nodes) {
        mNodes = nodes != null ? nodes : new ArrayList<>();
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_node, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        GatewayNode n = mNodes.get(position);
        h.name.setText(n.getName());
        h.port.setText(h.itemView.getContext().getString(
                R.string.node_port_line, n.getLocalPort()));
        // 显示模式标签 + 地址：302 模式显示 CF 地址，TXT/AUTO 模式显示入口域名
        String addr = n.getDiscoveryMethod() == GatewayNode.DISCOVERY_REDIRECT
                ? n.getCfUrl() : n.getStunDomain();
        String modeLabel = getModeLabel(n.getDiscoveryMethod());
        h.url.setText(addr + " [" + modeLabel + "]");

        boolean running = mManager != null && mManager.isRunning(n.getId());
        String err = mManager != null ? mManager.getError(n.getId()) : null;
        boolean hasError = !running && err != null && !err.isEmpty();

        Context ctx = h.itemView.getContext();
        // 状态文字 + 颜色
        if (running) {
            h.status.setText(R.string.status_running_short);
            h.status.setTextColor(ContextCompat.getColor(ctx, R.color.dot_running));
            h.card.setStrokeColor(ContextCompat.getColor(ctx, R.color.stroke_running));
        } else if (hasError) {
            h.status.setText(R.string.status_error_short);
            h.status.setTextColor(ContextCompat.getColor(ctx, R.color.stroke_error));
            h.card.setStrokeColor(ContextCompat.getColor(ctx, R.color.stroke_error));
        } else {
            h.status.setText(R.string.status_stopped_short);
            h.status.setTextColor(ContextCompat.getColor(ctx, R.color.dot_stopped));
            h.card.setStrokeColor(ContextCompat.getColor(ctx, R.color.stroke_neutral));
        }

        if (hasError) {
            h.error.setText(err);
            h.error.setVisibility(View.VISIBLE);
        } else {
            h.error.setVisibility(View.GONE);
        }

        // 切换开关时不触发回调，避免循环：先标记，设状态，再恢复
        h.enabled.setOnCheckedChangeListener(null);
        h.enabled.setChecked(n.isEnabled());
        h.enabled.setOnCheckedChangeListener((b, checked) -> mCb.onToggleEnabled(n.getId(), checked));

        h.itemView.setOnClickListener(v -> mCb.onEdit(n));
        h.edit.setOnClickListener(v -> mCb.onEdit(n));
        h.remove.setOnClickListener(v -> mCb.onRemove(n.getId()));
    }

    @Override
    public int getItemCount() {
        return mNodes.size();
    }

    private String getModeLabel(int method) {
        switch (method) {
            case GatewayNode.DISCOVERY_TXT: return "TXT";
            case GatewayNode.DISCOVERY_AUTO: return "AUTO";
            default: return "302";
        }
    }

    static class VH extends RecyclerView.ViewHolder {
        final MaterialCardView card;
        final TextView name;
        final TextView status;
        final TextView port;
        final TextView url;
        final TextView error;
        final MaterialSwitch enabled;
        final View edit;
        final View remove;

        VH(View v) {
            super(v);
            card = (MaterialCardView) v;
            name = v.findViewById(R.id.nodeName);
            status = v.findViewById(R.id.nodeStatus);
            port = v.findViewById(R.id.nodePort);
            url = v.findViewById(R.id.nodeUrl);
            error = v.findViewById(R.id.nodeError);
            enabled = v.findViewById(R.id.nodeEnabledSwitch);
            edit = v.findViewById(R.id.btnEdit);
            remove = v.findViewById(R.id.btnRemove);
        }
    }
}
