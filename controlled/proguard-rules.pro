# Add project specific ProGuard rules here.
# Keep Shizuku API entry points
-keep class rikka.shizuku.** { *; }
-keep class dev.rikka.shizuku.** { *; }
-keep class dev.rikka.hidden.** { *; }

# Keep shared protocol models (reflection-free but be safe with serialization)
-keep class com.adbcontrol.shared.** { *; }

# Paho MQTT
-keep class org.eclipse.paho.** { *; }

# AWS S3
-keep class software.amazon.awssdk.** { *; }
-dontwarn software.amazon.awssdk.**
-dontwarn org.slf4j.**

# R8 missing_rules.txt(2026-08-25 assembleRelease 生成):
# netty tcnative / JNDI / log4j / jetty alpn 等均为 JVM 端可选依赖,
# Android 运行时不会触达,-dontwarn 抑制即可。
-dontwarn io.netty.internal.tcnative.**
-dontwarn javax.naming.**
-dontwarn org.apache.log4j.**
-dontwarn org.apache.logging.log4j.**
-dontwarn org.eclipse.jetty.alpn.**
-dontwarn org.eclipse.jetty.npn.**
-dontwarn org.ietf.jgss.**
-dontwarn reactor.blockhound.integration.BlockHoundIntegration
