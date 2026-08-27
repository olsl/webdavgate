# 保留 HttpCore / OkHttp 关键反射调用类，避免 Release 混淆后失效
-keep class org.apache.httpcore.** { *; }
-keep class okhttp3.** { *; }
-keep class okio.** { *; }
-keep class com.webdavgate.** { *; }
