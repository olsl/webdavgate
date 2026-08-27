package com.webdavgate;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.PowerManager;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.textfield.TextInputEditText;
import com.webdavgate.core.GatewayManager;
import com.webdavgate.log.LogStore;
import com.webdavgate.model.GatewayNode;
import com.webdavgate.service.GatewayService;
import com.webdavgate.ui.NodeAdapter;

/**
 * 主界面：多节点列表 + 服务总开关。
 *
 * <p>启动时 bindService 拿到 {@link GatewayManager}（服务 onCreate 即创建 manager），
 * 用于读取/编辑节点配置和查询运行状态。"启动网关"按钮才真正 startForegroundService
 * 把服务推入前台态并拉起 enabled 节点；"停止网关"则发 ACTION_STOP 释放前台态。
 */
public class MainActivity extends AppCompatActivity
        implements NodeAdapter.Callback, GatewayManager.Listener {

    private MaterialToolbar mToolbar;
    private View mStatusDot;
    private TextView mTvSummary;
    private View mEmptyCard;
    private RecyclerView mRecycler;
    private NodeAdapter mAdapter;
    private MaterialButton mBtnToggle;
    private FloatingActionButton mFabAdd;
    private MaterialButton mBtnLogs;

    private GatewayManager mManager;
    private boolean mBound;
    private AlertDialog mEditDialog;

    /** 通知权限申请（Android 13+） */
    private final ActivityResultLauncher<String> mNotiPermLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
                // 用户拒绝也不阻塞：前台服务仍可运行，只是通知可能被系统折叠
            });

    private final ServiceConnection mConn = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            GatewayService.LocalBinder b = (GatewayService.LocalBinder) service;
            mManager = b.getManager();
            mManager.addListener(MainActivity.this);
            mAdapter.setManager(mManager);
            refreshUi();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            // 仅进程崩溃时回调，正常 unbind 不走这里
            mManager = null;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        LogStore.i("MainActivity", "APP started (v1.2), building UI...");

        mToolbar = findViewById(R.id.toolbar);
        mStatusDot = findViewById(R.id.statusDot);
        mTvSummary = findViewById(R.id.tvRunningSummary);
        mEmptyCard = findViewById(R.id.emptyCard);
        mRecycler = findViewById(R.id.recyclerNodes);
        mBtnToggle = findViewById(R.id.btnToggleService);
        mFabAdd = findViewById(R.id.fabAdd);
        mBtnLogs = findViewById(R.id.btnLogs);

        setSupportActionBar(mToolbar);

        mRecycler.setLayoutManager(new LinearLayoutManager(this));
        mAdapter = new NodeAdapter(null, this);
        mRecycler.setAdapter(mAdapter);

        mBtnToggle.setOnClickListener(v -> toggleService());
        mFabAdd.setOnClickListener(v -> showEditDialog(null));
        findViewById(R.id.btnAddNode).setOnClickListener(v -> showEditDialog(null));
        mBtnLogs.setOnClickListener(v -> {
            startActivity(new Intent(this, LogViewerActivity.class));
        });

        // Android 13+ 申请通知权限，否则前台服务通知不可见
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            mNotiPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
        }

        // 绑定服务（BIND_AUTO_CREATE：服务不存在则创建 onCreate，但不触发 onStartCommand）
        Intent intent = new Intent(this, GatewayService.class);
        mBound = bindService(intent, mConn, Context.BIND_AUTO_CREATE);
        LogStore.d("MainActivity", "bindService result=" + mBound);
    }

    @Override
    protected void onDestroy() {
        if (mBound) {
            if (mManager != null) mManager.removeListener(this);
            unbindService(mConn);
            mBound = false;
        }
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshUi();
    }

    // ------------------------------------------------------------------
    // 服务总开关
    // ------------------------------------------------------------------

    private void toggleService() {
        if (mManager == null) return;
        Intent intent = new Intent(this, GatewayService.class);
        if (mManager.isActive()) {
            intent.setAction(GatewayService.ACTION_STOP);
        } else {
            // 节点为空时提示先配置
            if (mManager.getNodes().isEmpty()) {
                Toast.makeText(this, R.string.empty_hint, Toast.LENGTH_LONG).show();
                return;
            }
            intent.setAction(GatewayService.ACTION_START);
            // ColorOS/MIUI 等会把后台进程冻结导致网关无响应，
            // 启动服务时引导用户加入电池优化白名单（仅需确认一次）
            maybeRequestIgnoreBatteryOptimization();
            ContextCompat.startForegroundService(this, intent);
            return;
        }
        startService(intent);
    }

    /**
     * 若尚未加入电池优化白名单，弹系统确认框让用户放行。
     * 这是对抗 ColorOS/MIUI 缓存应用冻结最有效的一招，
     * 用户拒绝也不阻塞，只是网关在后台长时间停留后可能被冻结。
     */
    private void maybeRequestIgnoreBatteryOptimization() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm == null || pm.isIgnoringBatteryOptimizations(getPackageName())) return;
            // 每次启动最多提醒一次：拒绝过的用户不再骚扰（用 SharedPreferences 记住）
            android.content.SharedPreferences sp =
                    getSharedPreferences("webdavgate_settings", MODE_PRIVATE);
            if (sp.getBoolean("battery_prompt_dismissed", false)) return;

            new MaterialAlertDialogBuilder(this)
                    .setTitle("保活提示")
                    .setMessage("为避免锁屏/切后台后网关停止响应，建议把本 APP 加入电池优化白名单（忽略电池优化）。")
                    .setPositiveButton("去设置", (d, w) -> {
                        Intent i = new Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                android.net.Uri.parse("package:" + getPackageName()));
                        startActivity(i);
                    })
                    .setNegativeButton("不再提示", (d, w) ->
                            sp.edit().putBoolean("battery_prompt_dismissed", true).apply())
                    .show();
        } catch (Exception e) {
            LogStore.w("MainActivity", "battery optimization prompt failed: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // GatewayManager.Listener：状态变化时刷新（回调可能来自后台线程）
    // ------------------------------------------------------------------

    @Override
    public void onStateChanged() {
        runOnUiThread(this::refreshUi);
    }

    private void refreshUi() {
        if (mManager == null) return;
        boolean active = mManager.isActive();
        int running = mManager.getRunningCount();

        // 主按钮：文字 + 图标随状态切换
        mBtnToggle.setText(active ? R.string.stop_gateway : R.string.start_gateway);
        mBtnToggle.setIconResource(active
                ? android.R.drawable.ic_media_pause
                : android.R.drawable.ic_media_play);

        // 概览：Toolbar 副标题 + 操作卡文本
        String summary = active
                ? getString(R.string.summary_running, running)
                : getString(R.string.summary_none);
        mToolbar.setSubtitle(summary);
        mTvSummary.setText(summary);

        // 状态点颜色
        int dotColor = ContextCompat.getColor(this,
                active ? R.color.dot_running : R.color.dot_stopped);
        ViewCompat.setBackgroundTintList(mStatusDot, ColorStateList.valueOf(dotColor));

        mAdapter.setNodes(mManager.getNodes());
        mEmptyCard.setVisibility(mManager.getNodes().isEmpty() ? View.VISIBLE : View.GONE);
    }

    // ------------------------------------------------------------------
    // NodeAdapter.Callback
    // ------------------------------------------------------------------

    @Override
    public void onEdit(GatewayNode node) {
        showEditDialog(node);
    }

    @Override
    public void onRemove(String id) {
        if (mManager == null) return;
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.remove)
                .setMessage(R.string.confirm_remove)
                .setPositiveButton(R.string.remove, (d, w) -> mManager.removeNode(id))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    @Override
    public void onToggleEnabled(String id, boolean enabled) {
        if (mManager != null) mManager.setEnabled(id, enabled);
    }

    // ------------------------------------------------------------------
    // 节点编辑对话框（node==null 为新增，否则编辑）
    // ------------------------------------------------------------------

    private void showEditDialog(GatewayNode node) {
        if (mEditDialog != null && mEditDialog.isShowing()) return;

        View body = LayoutInflater.from(this).inflate(R.layout.dialog_node_edit, null, false);
        TextInputEditText etName = body.findViewById(R.id.editName);
        TextInputEditText etUrl = body.findViewById(R.id.editUrl);
        TextInputEditText etPort = body.findViewById(R.id.editPort);

        boolean edit = node != null;
        if (edit) {
            etName.setText(node.getName());
            etUrl.setText(node.getCfUrl());
            etPort.setText(String.valueOf(node.getLocalPort()));
        } else {
            etPort.setText("8888");
        }

        mEditDialog = new MaterialAlertDialogBuilder(this)
                .setView(body)
                .setTitle(edit ? R.string.dialog_edit_title : R.string.dialog_add_title)
                .setPositiveButton(R.string.save, (d, w) -> {
                    String name = textOf(etName);
                    String url = textOf(etUrl);
                    String portStr = textOf(etPort);
                    int port;
                    try {
                        port = Integer.parseInt(portStr);
                    } catch (NumberFormatException e) {
                        toast(R.string.err_port_range);
                        return;
                    }
                    if (TextUtils.isEmpty(name) || TextUtils.isEmpty(url)) {
                        toast(R.string.err_empty);
                        return;
                    }
                    if (port < 1024 || port > 65535) {
                        toast(R.string.err_port_range);
                        return;
                    }
                    // 端口冲突（编辑自身除外）
                    for (GatewayNode n : mManager.getNodes()) {
                        if (n.getLocalPort() == port && !n.getId().equals(edit ? node.getId() : "")) {
                            toast(R.string.err_port_dup);
                            return;
                        }
                    }
                    if (edit) {
                        node.setName(name);
                        node.setCfUrl(url);
                        node.setLocalPort(port);
                        mManager.updateNode(node);
                    } else {
                        mManager.addNode(new GatewayNode(name, url, port));
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .create();
        mEditDialog.show();
    }

    private static String textOf(TextInputEditText et) {
        return et.getText() == null ? "" : et.getText().toString().trim();
    }

    private void toast(int resId) {
        Toast.makeText(this, resId, Toast.LENGTH_SHORT).show();
    }
}
