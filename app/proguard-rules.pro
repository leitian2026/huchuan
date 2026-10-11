# OkHttp / Okio（4.x 自带 consumer 规则，这里只屏蔽可选依赖的告警）
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# ZXing 扫码
-keep class com.journeyapps.barcodescanner.** { *; }
-dontwarn com.google.zxing.**

# 保留崩溃堆栈的行号，便于排查
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
