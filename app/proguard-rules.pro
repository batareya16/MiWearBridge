# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.kts.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# LSPosed entry point — referenced by name from assets/xposed_init.
-keep class com.batareya16.miWearBridge.xp.MainHook { *; }

# Hook classes use reflection / DexKit; keep the whole module package intact.
-keep class com.batareya16.miWearBridge.xp.** { *; }

# Xposed API must not be stripped/renamed.
-keep class de.robv.android.xposed.** { *; }
-dontwarn de.robv.android.xposed.**

-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod