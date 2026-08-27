package com.webdavgate.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.webdavgate.R;
import com.webdavgate.log.LogEntry;
import com.webdavgate.log.LogStore;

import java.util.ArrayList;
import java.util.List;

/**
 * 日志列表适配器：展示时间戳、级别、标签和消息。
 */
public class LogAdapter extends RecyclerView.Adapter<LogAdapter.VH> {

    private final LogStore mLogStore;
    private List<LogEntry> mEntries = new ArrayList<>();

    public LogAdapter(LogStore logStore) {
        mLogStore = logStore;
        mEntries = logStore.getEntries();
    }

    public void reload() {
        mEntries = mLogStore.getEntries();
        notifyDataSetChanged();
    }

    public void append(LogEntry entry) {
        mEntries.add(entry);
        notifyItemInserted(mEntries.size() - 1);
    }

    public void clearAll() {
        mEntries.clear();
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_log, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        LogEntry e = mEntries.get(position);
        h.level.setText(LogEntry.levelName(e.level));
        h.level.setTextColor(LogEntry.levelColor(e.level));
        h.time.setText(mLogStore.formatTime(e.timestamp));
        h.tag.setText(e.tag != null ? e.tag : "?");
        h.message.setText(e.message);
    }

    @Override
    public int getItemCount() {
        return mEntries.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        final TextView level;
        final TextView time;
        final TextView tag;
        final TextView message;

        VH(View v) {
            super(v);
            level = v.findViewById(R.id.logLevel);
            time = v.findViewById(R.id.logTime);
            tag = v.findViewById(R.id.logTag);
            message = v.findViewById(R.id.logMessage);
        }
    }
}
