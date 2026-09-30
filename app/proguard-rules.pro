# NanoHTTPD is reflection-free but keeps a few resource lookups.
-keep class fi.iki.elonen.** { *; }
-dontwarn fi.iki.elonen.**
# zxing
-dontwarn com.google.zxing.**
# Kotlin serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class dev.cued.**$$serializer { *; }
-keepclassmembers class dev.cued.** { *** Companion; }
-keepclasseswithmembers class dev.cued.** { kotlinx.serialization.KSerializer serializer(...); }

# NewPipeExtractor + Rhino (JS engine for YouTube's throttling parameter)
-keep class org.schabi.newpipe.extractor.** { *; }
-keep class org.mozilla.javascript.** { *; }
-dontwarn org.mozilla.javascript.**
-dontwarn org.schabi.newpipe.extractor.**
# jaudiotagger references java.awt in code paths we never execute on Android
-dontwarn java.awt.**
-dontwarn javax.imageio.**
-keep class org.jaudiotagger.** { *; }
-keep class de.sciss.jump3r.** { *; }
-dontwarn javax.sound.**
