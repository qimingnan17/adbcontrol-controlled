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
