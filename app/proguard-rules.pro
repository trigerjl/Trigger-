# ============================================================
# Triger — правила R8/ProGuard для release-сборки
# ============================================================

# Общие атрибуты, нужные библиотекам (аннотации, generics, вложенные классы)
-keepattributes Signature, *Annotation*, InnerClasses, EnclosingMethod, Exceptions

# ---------- BouncyCastle ----------
-keep class org.bouncycastle.** { *; }

-keepclassmembers class **$$ExternalSyntheticLambda* { *; }
-keep class kotlin.Metadata { *; }
-keepclassmembers class **$WhenMappings {
    <fields>;
}

-dontwarn org.bouncycastle.**
-dontwarn org.slf4j.**
-dontwarn org.apache.logging.log4j.**
-dontwarn javax.annotation.**
-dontwarn javax.naming.**
-dontwarn java.lang.management.**
-dontwarn sun.misc.**
-dontwarn com.google.protobuf.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
-dontwarn reactor.blockhound.**
-dontwarn com.google.errorprone.**
-dontwarn org.codehaus.mojo.animal_sniffer.**

# ---------- Web3j ----------
-keep class org.web3j.** { *; }
-keep interface org.web3j.** { *; }
-dontwarn org.web3j.**
-dontwarn org.java_websocket.**

# ---------- Java-WebSocket ----------
-keep class org.java_websocket.** { *; }

# ---------- Android / AndroidX ----------
-keep class androidx.localbroadcastmanager.** { *; }
-dontwarn androidx.**

# Отладочная информация: убираем имена файлов/строк из стектрейсов
-renamesourcefileattribute SourceFile
