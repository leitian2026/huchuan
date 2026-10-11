# OkHttp / Okio（4.x 自带 consumer 规则，这里只屏蔽可选依赖的告警）
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
# 「ECH 检测」用反射看 Conscrypt 有哪些接口，别被压缩工具去掉
-keep class org.conscrypt.Conscrypt { public *; }
-keep class org.conscrypt.Conscrypt$* { public *; }
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# ZXing 扫码
-keep class com.journeyapps.barcodescanner.** { *; }
-dontwarn com.google.zxing.**

# 保留崩溃堆栈的行号，便于排查
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
