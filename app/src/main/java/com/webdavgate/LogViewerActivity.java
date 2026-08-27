package com.webdavgate;

import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.webdavgate.log.LogEntry;
import com.webdavgate.log.LogStore;
import com.webdavgate.ui.LogAdapter;

/**
 * 实时日志查看器：显示网关运行时的所有日志条目，支持暂停实时追加、清空历史。
 */
public class LogViewerActivity extends AppCompatActivity implements LogStore.LogObserver {

    private MaterialToolbar mToolbar;
    private TextView mTvCount;
    private TextView mTvEmpty;
    private RecyclerView mRecycler;
    private MaterialButton mBtnPause;
    private MaterialButton mBtnClear;

    private LogAdapter mAdapter;
    private boolean mPaused;
    private int mCurrentCount;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_log_viewer);

        mToolbar = findViewById(R.id.toolbar);
        mTvCount = findViewById(R.id.tvLogCount);
        mTvEmpty = findViewById(R.id.tvEmpty);
        mRecycler = findViewById(R.id.recyclerLogs);
        mBtnPause = findViewById(R.id.btnPause);
        mBtnClear = findViewById(R.id.btnClear);

        setSupportActionBar(mToolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setDisplayShowHomeEnabled(true);
        }

        mAdapter = new LogAdapter(LogStore.getInstance());
        mRecycler.setLayoutManager(new LinearLayoutManager(this));
        mRecycler.setAdapter(mAdapter);

        mBtnPause.setOnClickListener(v -> togglePause());
        mBtnClear.setOnClickListener(v -> {
            LogStore.getInstance().clear();
            mAdapter.clearAll();
            updateCount();
            updateEmpty();
        });

        updateCount();
        updateEmpty();
        LogStore.getInstance().addObserver(this);
    }

    @Override
    protected void onDestroy() {
        LogStore.getInstance().removeObserver(this);
        super.onDestroy();
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void togglePause() {
        mPaused = !mPaused;
        if (mPaused) {
            mBtnPause.setText(R.string.log_resume);
            mBtnPause.setIconResource(android.R.drawable.ic_media_play);
        } else {
            mBtnPause.setText(R.string.log_pause);
            mBtnPause.setIconResource(android.R.drawable.ic_media_pause);
            // 恢复时立即刷新一次，把暂停期间漏看的日志补上
            mAdapter.reload();
            scrollToBottom();
        }
    }

    // ------------------------------------------------------------------
    // LogStore.LogObserver（可能来自后台线程）
    // ------------------------------------------------------------------

    @Override
    public void onLogAdded(LogEntry entry) {
        if (mPaused) return;
        runOnUiThread(() -> {
            mAdapter.append(entry);
            updateCount();
            updateEmpty();
            scrollToBottom();
        });
    }

    @Override
    public void onLogsCleared() {
        runOnUiThread(() -> {
            mAdapter.clearAll();
            updateCount();
            updateEmpty();
        });
    }

    private void scrollToBottom() {
        if (mAdapter.getItemCount() > 0) {
            mRecycler.scrollToPosition(mAdapter.getItemCount() - 1);
        }
    }

    private void updateCount() {
        int size = mAdapter.getItemCount();
        if (size != mCurrentCount) {
            mCurrentCount = size;
            mTvCount.setText(getString(R.string.log_count, size));
        }
    }

    private void updateEmpty() {
        mTvEmpty.setVisibility(mAdapter.getItemCount() == 0 ? View.VISIBLE : View.GONE);
    }
}
