# CutLab Release R8 / ProGuard Configuration (Phase 10)

# Preserve line numbers and source filenames for readable crash diagnostics
-keepattributes SourceFile,LineNumberTable,*Annotation*,Signature,InnerClasses,EnclosingMethod
-renamesourcefileattribute SourceFile

# Kotlin Serialization (@Serializable project schema, benchmark records, export index, waveforms)
-keepattributes RuntimeVisibleAnnotations,RuntimeInvisibleAnnotations
-keepclassmembers class * {
    @kotlinx.serialization.Serializable <init>(...);
}
-keep,includedescriptorclasses class com.example.engine.project.**$$serializer { *; }
-keepclassmembers class com.example.engine.project.** {
    *** Companion;
}
-keep,includedescriptorclasses class com.example.core.performance.**$$serializer { *; }
-keepclassmembers class com.example.core.performance.** {
    *** Companion;
}
-keep,includedescriptorclasses class com.example.engine.export.**$$serializer { *; }
-keepclassmembers class com.example.engine.export.** {
    *** Companion;
}
-keep,includedescriptorclasses class com.example.engine.audio.**$$serializer { *; }
-keepclassmembers class com.example.engine.audio.** {
    *** Companion;
}
-keep,includedescriptorclasses class com.example.*Route* { *; }
-keep,includedescriptorclasses class com.example.*Route$$serializer { *; }

# Room Database Entities, DAOs, and Migrations
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao class * { *; }
-dontwarn androidx.room.paging.**

# AndroidX Media3 (ExoPlayer, Transformer, Effect)
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# Kotlin Coroutines
-dontwarn kotlinx.coroutines.**

# OpenGL ES / JNI native surface callbacks
-keepclassmembers class com.example.core.graphics.GlCompositorRenderer {
    public *;
}
