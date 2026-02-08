# Google API Client
-keep class com.google.api.** { *; }
-keep class com.google.http.** { *; }
-dontwarn com.google.api.**
-dontwarn org.apache.http.**
-dontwarn com.sun.net.httpserver.**

# Google API Services (Drive model classes use reflection for JSON parsing)
-keep class com.google.api.services.drive.model.** { *; }

# Google Auth
-keep class com.google.android.gms.auth.** { *; }

# Jackson (used by google-api-client for JSON serialization)
-keep class com.fasterxml.jackson.** { *; }
-dontwarn com.fasterxml.jackson.**

# Kotlinx Serialization (used by type-safe navigation routes)
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
