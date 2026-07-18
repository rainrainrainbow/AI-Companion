# Add project specific ProGuard rules here.
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod

# Keep JNI natives
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep llama.cpp
-keep class com.ai.companion.core.llm.** { *; }

# Keep whisper
-keep class com.ai.companion.core.audio.STTEngine { *; }

# Keep VITS
-keep class com.ai.companion.core.audio.TTSEngine { *; }

# Keep Filament
-keep class com.google.android.filament.** { *; }
-dontwarn com.google.android.filament.**

# Keep Gson
-keepattributes Signature
-keep class com.google.gson.** { *; }
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# Keep Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# Keep Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}