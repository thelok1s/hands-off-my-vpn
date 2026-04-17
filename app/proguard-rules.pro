# ─── Keep Xposed / YukiHookAPI entry points ───────────────────────────────────
# The KSP processor writes the class name into xposed_init / META-INF assets.
# If ProGuard renames HookEntry the module will fail to load.
-keep class dev.lok1s.handoffmyvpn.HookEntry { *; }

# Keep the hook objects (Kotlin object singletons) so reflection inside
# YukiHookAPI can always find the hook methods.
-keep class dev.lok1s.handoffmyvpn.hook.** { *; }

# YukiHookAPI itself must not be obfuscated.
-keep class com.highcapable.yukihookapi.** { *; }

# Keep the YukiHookAPI annotation processor output.
-keep @com.highcapable.yukihookapi.annotation.xposed.InjectYukiHookWithXposed class * { *; }

# ─── JNI / Native layer ──────────────────────────────────────────────────────
# Prevent R8 from stripping native method declarations and the NativeLoader.
-keepclasseswithmembernames class * { native <methods>; }
-keep class dev.lok1s.handoffmyvpn.hook.NativeLoader { *; }

# ─── General Android rules ────────────────────────────────────────────────────
-keepattributes *Annotation*
-keepattributes SourceFile, LineNumberTable
-renamesourcefileattribute SourceFile

# Suppress warnings for APIs that exist only on device.
-dontwarn android.net.**
-dontwarn java.net.NetworkInterface
