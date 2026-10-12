# Retrobit Voice — R8/ProGuard rules
# Dipakai saat isMinifyEnabled = true (lihat app/build.gradle.kts).

# Kotlinx Serialization: simpan serializer yang di-generate
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class dev.retrobit.voice.** {
    *** Companion;
}
-keepclasseswithmembers class dev.retrobit.voice.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class dev.retrobit.voice.**$$serializer { *; }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Komponen sistem Android yang di-instansiasi via manifest
-keep class dev.retrobit.voice.dictation.ime.RetrobitImeService { *; }
-keep class dev.retrobit.voice.dictation.overlay.OverlayBubbleService { *; }
-keep class dev.retrobit.voice.dictation.a11y.TextInsertAccessibilityService { *; }
-keep class dev.retrobit.voice.assistant.VoiceAssistantService { *; }
-keep class dev.retrobit.voice.ui.QuickAssistantTileService { *; }
