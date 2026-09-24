# 书源规则、设置等通过 Gson 反射序列化
-keep class com.wang.sonovel.data.** { *; }
-keepattributes Signature, *Annotation*, InnerClasses, EnclosingMethod

# QuickJS (JNI)
-keep class com.whl.quickjs.** { *; }

# jsoup 可选依赖
-dontwarn com.google.re2j.**
-dontwarn org.jspecify.annotations.**

# OkHttp
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

-keepattributes SourceFile,LineNumberTable
