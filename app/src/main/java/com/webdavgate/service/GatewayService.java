package com.webdavgate.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import androidx.core.app.NotificationCompat;

import com.webdavgate.MainActivity;
import com.webdavgate.R;
import com.webdavgate.core.GatewayManager;
import com.webdavgate.log.LogStore;

/**
 * 前台服务：让网关在用户离开 App / 锁屏后仍能持续运行，避免被系统回收。
 *
 * <p>这是 Android 上"常驻后台"的唯一可靠姿势——普通后台服务在内存压力下会被杀，
 * 而前台服务靠一条常驻通知换取更高的存活优先级。
 *
 * <p>生命周期：
 * <ul>
 *   <li>{@link #ACTION_START}：建渠道 + startForeground + manager.startAll()；</li>
 *   <li>{@link #ACTION_STOP}：manager.stopAll() + stopForeground + stopSelf。</li>
 * </ul>
 *
 * <p>UI 通过 bindService 拿到 {@link LocalBinder} 进而操作 {@link GatewayManager}，
 * 避免每次交互都走 Intent。
 */
public class GatewayService extends Service {

    private static final String TAG = "GatewayService";

    public static final String ACTION_START = "com.webdavgate.action.START";
    public static final String ACTION_STOP = "com.webdavgate.action.STOP";

    private static final String CHANNEL_ID = "webdavgate_service";
    private static final int NOTIFICATION_ID = 1;

    private final IBinder mBinder = new LocalBinder();
    private GatewayManager mManager;

    /** CPU 唤醒锁：防止进程被冻结 / CPU 休眠导致网关无响应 */
    private PowerManager.WakeLock mWakeLock;
    /** Wi-Fi 高性能锁：防止 Wi-Fi 省电模式拖慢 STUN 直连吞吐 */
    private WifiManager.WifiLock mWifiLock;

    /** UI 侧拿到 manager 的入口 */
    public class LocalBinder extends Binder {
        public GatewayManager getManager() {
            return mManager;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        LogStore.i(TAG, "onCreate: creating GatewayManager");
        mManager = new GatewayManager(this);
        mManager.addListener(this::onManagerStateChanged);
        createNotificationChannel();
        LogStore.i(TAG, "onCreate: ready, node count=" + mManager.getNodes().size());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        LogStore.i(TAG, "onStartCommand: action=" + action + ", isActive=" + (mManager != null && mManager.isActive()));
        if (ACTION_STOP.equals(action)) {
            LogStore.i(TAG, "STOP action received");
            if (mManager != null) mManager.stopAll();
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        // ACTION_START 或 null（被系统重建时）：确保前台态 + 拉起节点
        startForegroundWithDataSync(buildNotification());
        acquireKeepAliveLocks();
        if (mManager != null && !mManager.isActive()) {
            LogStore.i(TAG, "Starting all nodes...");
            mManager.startAll();
        } else if (mManager != null) {
            LogStore.i(TAG, "Already active, skipping startAll");
        }
        // 服务被杀后系统重建时尽量恢复，保证常驻
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        releaseKeepAliveLocks();
        if (mManager != null) {
            mManager.stopAll();
        }
        super.onDestroy();
    }

    /**
     * 获取 CPU 唤醒锁与 Wi-Fi 高性能锁。
     *
     * <p>ColorOS/MIUI 等国产 ROM 会对退到后台的应用进程做"缓存应用冻结"，
     * 即使有前台服务也可能被冻结，表现为 NanoHTTPD 完全不响应、
     * CX 访问 127.0.0.1:8888 卡住报错；切回网关界面解冻后立即恢复。
     * PARTIAL_WAKE_LOCK 可显著降低被冻结概率；配合电池优化白名单效果最佳。
     */
    private void acquireKeepAliveLocks() {
        try {
            if (mWakeLock == null) {
                PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                if (pm != null) {
                    mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WebDavGate:gateway");
                    mWakeLock.setReferenceCounted(false);
                }
            }
            if (mWakeLock != null && !mWakeLock.isHeld()) {
                mWakeLock.acquire();
                LogStore.i(TAG, "WakeLock acquired");
            }
        } catch (Exception e) {
            LogStore.w(TAG, "acquire WakeLock failed: " + e.getMessage());
        }
        try {
            if (mWifiLock == null) {
                WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                if (wm != null) {
                    mWifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "WebDavGate:wifi");
                    mWifiLock.setReferenceCounted(false);
                }
            }
            if (mWifiLock != null && !mWifiLock.isHeld()) {
                mWifiLock.acquire();
                LogStore.i(TAG, "WifiLock acquired");
            }
        } catch (Exception e) {
            LogStore.w(TAG, "acquire WifiLock failed: " + e.getMessage());
        }
    }

    /** 释放保活锁（停止服务或销毁时调用） */
    private void releaseKeepAliveLocks() {
        try {
            if (mWakeLock != null && mWakeLock.isHeld()) mWakeLock.release();
            if (mWifiLock != null && mWifiLock.isHeld()) mWifiLock.release();
            LogStore.i(TAG, "Keep-alive locks released");
        } catch (Exception ignored) { }
    }

    /** manager 状态变化回调（来自任意线程），刷新通知内容 */
    private void onManagerStateChanged() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(NOTIFICATION_ID, buildNotification());
        }
    }

    // ------------------------------------------------------------------
    // 通知
    // ------------------------------------------------------------------

    /** Android 8.0+ 必须先建渠道才能弹通知。重复调用幂等。 */
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.noti_channel_name),
                    NotificationManager.IMPORTANCE_LOW); // LOW：无声，避免打扰
            ch.setDescription(getString(R.string.noti_channel_desc));
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.createNotificationChannel(ch);
            }
        }
    }

    private Notification buildNotification() {
        // 点击通知回到 MainActivity
        Intent openIntent = new Intent(this, MainActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            piFlags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent contentPi = PendingIntent.getActivity(this, 0, openIntent, piFlags);

        // 通知里的"停止"操作
        Intent stopIntent = new Intent(this, GatewayService.class).setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 1, stopIntent, piFlags);

        int running = mManager != null ? mManager.getRunningCount() : 0;
        String content = getString(R.string.noti_content, running);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(content)
                .setContentIntent(contentPi)
                .setOngoing(true)
                .setShowWhen(false)
                .addAction(0, getString(R.string.noti_action_stop), stopPi)
                .build();
    }

    private void startForegroundWithDataSync(Notification n) {
        // Android 14 (API 34) 起需显式声明 foregroundServiceType；
        // manifest 已声明 dataSync，这里三参重载与之匹配；低版本退化为两参。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, n,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIFICATION_ID, n);
        }
    }
}
