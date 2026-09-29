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
