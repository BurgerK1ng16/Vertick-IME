# Keep Android input method service and Room-generated implementations reachable.
-keep class com.weike.ime.ime.WeikeInputMethodService { *; }
-keep class com.weike.ime.data.LexiconDatabase { *; }
-keep class * extends androidx.room.RoomDatabase { *; }
# librime_jni resolves these classes and callback methods dynamically in JNI_OnLoad.
# Their binary names must remain compatible with the Trime ABI.
-keep class com.osfans.trime.core.** { *; }

# JNI entry points are looked up by their fixed names.
-keepclasseswithmembernames class * {
    native <methods>;
}
