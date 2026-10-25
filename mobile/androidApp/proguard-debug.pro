# Keep the debug APK's runtime and instrumentation API unchanged. AndroidX Core's
# deprecated address-linkifier is unused by this Compose client; only that family
# is eligible for removal, so both debug and production DEX satisfy the same ban.
-dontoptimize
-dontobfuscate
-keepattributes *
-keep class !androidx.core.text.util.LinkifyCompat** { *; }

# Preserve-all debug also retains published optional APIs that ordinary D8 leaves
# unresolved: Ktor's IntelliJ/JVM debugger probe and JNI Zero's generated loader
# override. Android does not execute these paths; keep the exceptions exact.
-dontwarn java.lang.management.ManagementFactory
-dontwarn java.lang.management.RuntimeMXBean
-dontwarn org.jni_zero.JniZeroJni
