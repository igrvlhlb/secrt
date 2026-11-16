# ProGuard rules for the desktop release build (`packageRelease*`), on top of Compose's defaults
# (Kotlin, Skiko, coroutines, kotlinx.serialization). Names aren't obfuscated (Compose sets
# -dontobfuscate), so these only stop ProGuard from removing code that is reached by reflection,
# JNI, JNA or ServiceLoader, which it can't see. A missing rule only shows when the packaged app
# runs: try it after adding such a library (docs/release.md).

# libsodium through JNA (com.ionspin's bindings). JNA looks up native functions by the Library
# interface's method names, reads Structure fields by name, and its native side calls back into
# its own Java classes.
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.Library { *; }
-keep class * extends com.sun.jna.Structure { *; }
-keep class com.ionspin.kotlin.crypto.** { *; }
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

# SQLite driver (SQLite3MultipleCiphers): JNI code calls back into org.sqlite classes, and JDBC
# finds the driver through META-INF/services.
-keep class org.sqlite.** { *; }

# EnumMap, EnumSet and Enum.valueOf(Class, String) find an enum's constants through its
# values() method, which ProGuard removes when nothing calls it directly (ZXing's QR code hints).
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# OkHttp (cloud backup) uses these TLS providers and GraalVM hooks only when they are present,
# and they aren't.
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn com.oracle.svm.**
-dontwarn org.graalvm.nativeimage.**
