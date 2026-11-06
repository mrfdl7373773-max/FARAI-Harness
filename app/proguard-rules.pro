# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class dev.harness.**$$serializer { *; }
-keepclassmembers class dev.harness.** { *** Companion; }
-keepclasseswithmembers class dev.harness.** { kotlinx.serialization.KSerializer serializer(...); }
# OkHttp
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
# Tink (security-crypto)
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**

# jsoup (web_search parsers)
-keep class org.jsoup.** { *; }
-dontwarn org.jspecify.annotations.**
-dontwarn com.google.re2j.**
