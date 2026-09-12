# GenieX SDK: bean classes are constructed from native code by field/ctor
# signature and the jni.* classes are resolved by name from Rust, so neither
# survives renaming.
-keep class com.geniex.sdk.** { *; }
-keepclasseswithmembernames class com.geniex.sdk.** {
    native <methods>;
}

# kotlinx.serialization generated serializers for the AI response DTOs.
-keepclassmembers class com.veritransit.inspector.ai.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.veritransit.inspector.ai.**$$serializer { *; }
