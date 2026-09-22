# Agora RTC
-keep class io.agora.**{*;}
-dontwarn io.agora.**

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.quietstack.voicetrade.**$$serializer { *; }
-keepclassmembers class com.quietstack.voicetrade.** { *** Companion; }
-keepclasseswithmembers class com.quietstack.voicetrade.** { kotlinx.serialization.KSerializer serializer(...); }

# Retrofit
-keepattributes Signature, Exceptions
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}
-dontwarn org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement
-dontwarn javax.annotation.**
-dontwarn kotlin.Unit
-dontwarn retrofit2.KotlinExtensions
-dontwarn retrofit2.KotlinExtensions$*

# Tink (via security-crypto) references compile-only annotations
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.concurrent.**
