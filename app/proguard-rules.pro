# JDBC-драйвер грузится через ServiceLoader и рефлексию.
-keep class com.mysql.** { *; }
-keep class org.mariadb.jdbc.** { *; }
-keep class * implements java.sql.Driver
-dontwarn com.mysql.**
-dontwarn org.mariadb.jdbc.**
-dontwarn javax.naming.**
-dontwarn java.sql.**
-dontwarn javax.sql.**
-dontwarn com.sun.jna.**
-dontwarn waffle.**
-dontwarn org.slf4j.**
-dontwarn software.amazon.awssdk.**

# LiveKit / WebRTC
-keep class livekit.** { *; }
-keep class io.livekit.android.** { *; }
-keep class org.webrtc.** { *; }
-dontwarn org.webrtc.**

-keep class com.pismo.messenger.data.model.** { *; }
