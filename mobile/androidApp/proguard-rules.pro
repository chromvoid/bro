# Instrumentation exercises these entry points and typed routes directly.
# Keep the application contract stable; R8 removes unused third-party compatibility code.
# Generated translations use resource names; resource shrinking is deliberately not enabled.
-keep class assistant.mobile.** { *; }

# The separate release instrumentation APK deduplicates these dependencies against
# this production APK. Preserve their shared ABI, including Kotlin multifile facade
# inheritance: runner bootstrap and typed upgrade assertions call these APIs directly.
-keep class kotlin.** { *; }
-keep class kotlinx.coroutines.** { *; }
-keep class kotlinx.serialization.** { *; }

# Filament/gltfio JNI reads Java fields by name, including Box.mCenterX/Y/Z.
# The 1.78.0 consumer rules do not cover the unannotated Box members.
# https://github.com/google/filament/blob/v1.78.0/android/proguard-rules.pro
# https://github.com/google/filament/blob/v1.78.0/android/filament-android/src/main/java/com/google/android/filament/Box.java
-keep class com.google.android.filament.** { *; }

# WebRTC's AAR generator packages no consumer rules. Preserve native-to-Java
# callbacks using the upstream SDK rules, adapted to our unprefixed org.webrtc.
# Java-to-native names/descriptors are covered by the JNI Zero native rule below.
# https://github.com/webrtc-sdk/webrtc/blob/m150_release/tools_webrtc/android/build_aar.py
# https://github.com/livekit/client-sdk-android/blob/main/livekit-android-sdk/consumer-rules.pro
-keepclasseswithmembers,includedescriptorclasses class * {
    @org.webrtc.CalledByNative <methods>;
}
-keepclasseswithmembers,includedescriptorclasses class * {
    @org.webrtc.CalledByNativeUnchecked <methods>;
}

# JNI Zero is bundled in that same WebRTC AAR, without its upstream rules.
# Preserve annotated callbacks/fields, native entry points and multiplexed names.
# https://chromium.googlesource.com/chromium/src/third_party/+/7c92732938de0ef7e28f5da231994723f938f407/jni_zero/proguard.flags
-keepclasseswithmembers,allowaccessmodification class ** {
    @**org.jni_zero.AccessedByNative <fields>;
}
-keepclasseswithmembers,includedescriptorclasses,allowaccessmodification,allowoptimization class ** {
    @**org.jni_zero.CalledByNative <methods>;
}
-keepclasseswithmembers,includedescriptorclasses,allowaccessmodification,allowoptimization class ** {
    @**org.jni_zero.CalledByNativeUnchecked <methods>;
}
-keepclasseswithmembernames,includedescriptorclasses,allowaccessmodification class ** {
    native <methods>;
}
-keepclasseswithmembers class !cr_allowunused,**J.N {
    public long *_HASH;
}

# ONNX JNI looks up Java classes and members by their original names.
# https://onnxruntime.ai/docs/build/android.html#note-proguard-rules-for-r8-minimization-android-app-builds-to-work
-keep class ai.onnxruntime.** { *; }

# AndroidJUnitRunner and ActivityScenario share this facade with the target APK.
# Preserve the existing API that the separate release instrumentation calls at bootstrap.
-keep class androidx.tracing.Trace { *; }
