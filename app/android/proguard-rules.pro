# R8 rules for the release build. Most libraries ship their own (SQLCipher, kotlinx-serialization,
# coroutines, Ktor, CameraX, Compose); these cover the ones that don't.

# libsodium is called through JNA (com.ionspin's bindings ship no rules). JNA finds the native
# functions by the names of the Library interface's methods, reads Structure fields by name, and
# its native side calls back into its own Java classes, so none of them may be renamed or removed.
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.Library { *; }
-keep class * extends com.sun.jna.Structure { *; }
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
# Desktop-only parts of JNA, never reached on Android.
-dontwarn java.awt.**
