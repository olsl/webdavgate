package com.webdavgate.log;

/**
 * 单条日志记录：时间戳 + 级别 + 标签 + 消息。
 */
public class LogEntry {

    public static final int VERBOSE = 0;
    public static final int DEBUG   = 1;
    public static final int INFO    = 2;
    public static final int WARN    = 3;
    public static final int ERROR   = 4;

    public final long timestamp;
    public final int level;
    public final String tag;
    public final String message;

    public LogEntry(long timestamp, int level, String tag, String message) {
        this.timestamp = timestamp;
        this.level = level;
        this.tag = tag;
        this.message = message;
    }

    public static String levelName(int level) {
        switch (level) {
            case VERBOSE: return "V";
            case DEBUG:   return "D";
            case INFO:    return "I";
            case WARN:    return "W";
            case ERROR:   return "E";
            default:      return "?";
        }
    }

    public static int levelColor(int level) {
        switch (level) {
            case VERBOSE: return 0xFF9E9E9E;
            case DEBUG:   return 0xFF2196F3;
            case INFO:    return 0xFF4CAF50;
            case WARN:    return 0xFFFF9800;
            case ERROR:   return 0xFFF44336;
            default:      return 0xFF9E9E9E;
        }
    }
}
