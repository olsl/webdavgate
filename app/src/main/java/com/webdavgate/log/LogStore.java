package com.webdavgate.log;

import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 全局日志存储：环形缓冲（最多 {@link #MAX_ENTRIES} 条），观察者模式通知 UI 刷新。
 *
 * <p>设计要点：
 * <ul>
 *   <li>用 {@link CopyOnWriteArrayList} 存观察者，后台线程 addLog 与 UI 线程注册/注销不冲突；</li>
 *   <li>日志超过上限时丢弃最旧条目，避免长时间运行导致内存膨胀；</li>
 *   <li>静态单例，整个 App 生命周期内唯一实例。</li>
 * </ul>
 */
public class LogStore {

    private static final int MAX_ENTRIES = 500;

    private static final LogStore INSTANCE = new LogStore();

    private final List<LogEntry> mEntries = new ArrayList<>();
    private final List<LogObserver> mObservers = new CopyOnWriteArrayList<>();
    private final SimpleDateFormat mDateFormat =
            new SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault());
    private final Object mLock = new Object();

    public interface LogObserver {
        void onLogAdded(LogEntry entry);
        void onLogsCleared();
    }

    public static LogStore getInstance() {
        return INSTANCE;
    }

    public void addObserver(LogObserver observer) {
        if (observer != null && !mObservers.contains(observer)) {
            mObservers.add(observer);
        }
    }

    public void removeObserver(LogObserver observer) {
        mObservers.remove(observer);
    }

    /** 添加一条日志；超过上限时丢掉最旧的；同时写入 Android logcat */
    public void log(int level, String tag, String message) {
        if (message == null) message = "";
        LogEntry entry = new LogEntry(System.currentTimeMillis(), level, tag, message);
        synchronized (mLock) {
            while (mEntries.size() >= MAX_ENTRIES) {
                mEntries.remove(0);
            }
            mEntries.add(entry);
        }
        // 同时写入 logcat，便于 adb logcat 排查
        try {
            switch (level) {
                case LogEntry.VERBOSE: Log.v(tag, message); break;
                case LogEntry.DEBUG:   Log.d(tag, message); break;
                case LogEntry.INFO:    Log.i(tag, message); break;
                case LogEntry.WARN:    Log.w(tag, message); break;
                case LogEntry.ERROR:   Log.e(tag, message); break;
            }
        } catch (Exception ignored) { }
        for (LogObserver o : mObservers) {
            try { o.onLogAdded(entry); } catch (Exception ignored) { }
        }
    }

    /** 获取当前所有日志（副本） */
    public List<LogEntry> getEntries() {
        synchronized (mLock) {
            return new ArrayList<>(mEntries);
        }
    }

    public int size() {
        synchronized (mLock) {
            return mEntries.size();
        }
    }

    /** 清空所有日志 */
    public void clear() {
        synchronized (mLock) {
            mEntries.clear();
        }
        for (LogObserver o : mObservers) {
            try { o.onLogsCleared(); } catch (Exception ignored) { }
        }
    }

    /** 格式化时间戳为 HH:mm:ss.SSS */
    public String formatTime(long timestamp) {
        return mDateFormat.format(new Date(timestamp));
    }

    // ------------------------------------------------------------------
    // 便捷方法
    // ------------------------------------------------------------------

    public static void v(String tag, String msg) { INSTANCE.log(LogEntry.VERBOSE, tag, msg); }
    public static void d(String tag, String msg) { INSTANCE.log(LogEntry.DEBUG, tag, msg); }
    public static void i(String tag, String msg) { INSTANCE.log(LogEntry.INFO, tag, msg); }
    public static void w(String tag, String msg) { INSTANCE.log(LogEntry.WARN, tag, msg); }
    public static void w(String tag, String msg, Throwable t) {
        String full = msg + (t != null ? "\n" + Log.getStackTraceString(t) : "");
        INSTANCE.log(LogEntry.WARN, tag, full);
    }
    public static void e(String tag, String msg) { INSTANCE.log(LogEntry.ERROR, tag, msg); }
    public static void e(String tag, String msg, Throwable t) {
        String full = msg + (t != null ? "\n" + Log.getStackTraceString(t) : "");
        INSTANCE.log(LogEntry.ERROR, tag, full);
    }
}
