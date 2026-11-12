# SPDX-License-Identifier: Apache-2.0

# Kotlinx Serialization & Navigation 3 NavKey destinations
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.icanbenchurcat.voicenotes.**$$serializer { *; }
-keepclassmembers class com.icanbenchurcat.voicenotes.** {
    *** Companion;
}
-keepclasseswithmembers class com.icanbenchurcat.voicenotes.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep class * implements androidx.navigation3.runtime.NavKey { *; }

# Room database entities, DAOs, and generated implementations
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
-keep class com.icanbenchurcat.voicenotes.data.** { *; }

# WorkManager workers instantiated via reflection/WorkerFactory
-keep class com.icanbenchurcat.voicenotes.service.SortingWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# ML Kit GenAI Speech Recognition & Common
-keep class com.google.mlkit.genai.** { *; }
-dontwarn com.google.mlkit.genai.**
