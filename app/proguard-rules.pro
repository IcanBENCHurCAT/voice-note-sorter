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

# ML Kit GenAI Speech Recognition, Prompt & Schema (ServiceLoader + Gson reflection)
-keep class com.google.mlkit.genai.** { *; }
-keep @com.google.mlkit.genai.schema.annotations.Generable class * { *; }
-keep class * implements com.google.mlkit.genai.schema.guided.GenerableProvider { *; }
-dontwarn com.google.mlkit.genai.**

# ML Kit ComponentDiscovery / Firebase Components
# Legacy firebase-components:16.1.0 ships a bodyless `-keep class * implements ComponentRegistrar`
# rule; under R8 full mode that strips `<init>()` from CommonComponentRegistrar, TextRegistrar,
# and VisionCommonRegistrar, breaking MlKitInitProvider (GenAI Prompt, Speech, and Vision OCR).
-keep class * implements com.google.firebase.components.ComponentRegistrar {
    public <init>();
    public java.util.List getComponents();
}
