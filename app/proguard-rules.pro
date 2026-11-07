# JARVIS ProGuard / R8 rules.
#
# The release build is minified. Two things must survive that:
#
#  1. The JNI bridge. The native code looks these classes up by their exact
#     fully-qualified names (see `Java_com_example_voiceassistant_generation_llama_LlamaNative_*`
#     in app/src/main/cpp/jarvis_llama.cpp). If R8 renames or removes any of
#     them, the native calls fail at runtime with UnsatisfiedLinkError.
#
#  2. The callback object. The native side calls `onToken([B)V` on it by
#     JNI method signature, so the class name and the method must not change.

# --- llama.cpp JNI bridge ---------------------------------------------------

# The object whose native methods are called. The `native*` methods are
# resolved by name from C, so the class and its members must keep their names.
-keep class com.example.voiceassistant.generation.llama.LlamaNative { *; }

# The JNI callback. The native code resolves `onToken([B)V` reflectively.
-keep class com.example.voiceassistant.generation.llama.LlamaNative$TokenSink {
    public void onToken(byte[]);
}

# Everything else in the llama package is reached from Kotlin, but keeping it
# costs nothing and protects against R8 deciding a class is unused when it is
# only used through the native layer.
-keep class com.example.voiceassistant.generation.llama.** { *; }

# Native methods in general: R8 must not strip a method that has a
# corresponding JNI entry point.
-keepclasseswithmembernames class * {
    native <methods>;
}

# --- Room ------------------------------------------------------------------

# Room generates an implementation of the @Database class and looks it up by
# name at runtime.
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-dontwarn androidx.room.paging.**

# --- Hilt / Dagger ---------------------------------------------------------

# Hilt's generated components are looked up reflectively.
-keep class dagger.hilt.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.ViewComponentManager$FragmentContextWrapper
-keepclasseswithmembernames class * { @javax.inject.Inject <init>(...); }

# --- sherpa-onnx -----------------------------------------------------------

# The sherpa-onnx JNI library is called from Java code that references these
# classes by name through the native layer.
-keep class com.k2fsa.sherpa.onnx.** { *; }
-dontwarn com.k2fsa.sherpa.onnx.**

# --- Kotlin coroutines -----------------------------------------------------

-dontwarn kotlinx.coroutines.**
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }
