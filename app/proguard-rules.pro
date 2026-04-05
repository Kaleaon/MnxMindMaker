# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in the SDK tools' default ProGuard config.

-keepattributes *Annotation*
-keep class com.kaleaon.mnxmindmaker.mnx.** { *; }

# llmedge integration safety rules (applies when llmedge is present as module/AAR).
# Keep JNI entry points and avoid obfuscating native-facing classes.
-keep class ** implements java.lang.AutoCloseable { *; }
-keep class ai.llmedge.** { *; }
-keep class org.llmedge.** { *; }
-keep class com.llmedge.** { *; }
-keepclassmembers class * {
    native <methods>;
}
