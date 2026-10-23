# WebRTC's JNI bridge resolves these methods and fields by name at runtime.
-keep class org.webrtc.** { *; }
-keepclasseswithmembernames class * { native <methods>; }
