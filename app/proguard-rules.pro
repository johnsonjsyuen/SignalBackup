# ---- Logging: strip verbose/debug/info logs from release builds ----
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}

# ---- Keep annotations/signatures needed for reflection-based JSON mapping ----
-keepattributes Signature, *Annotation*, InnerClasses, EnclosingMethod

# ---- google-http-client / google-api-client ----
# GenericData/GenericJson subclasses are populated reflectively via @com.google.api.client.util.Key fields.
-keepclassmembers class * {
    @com.google.api.client.util.Key <fields>;
}
-keep class * extends com.google.api.client.json.GenericJson { *; }
-keep class * extends com.google.api.client.util.GenericData { *; }
# Request classes (Drive.Files.List etc.) carry @Key query parameters and are created reflectively by the client.
-keep class * extends com.google.api.client.googleapis.services.AbstractGoogleClientRequest { *; }
-keep class com.google.api.client.util.** { *; }
-keep class com.google.api.client.json.** { *; }
-keep class com.google.api.client.http.** { *; }

# Drive v3 model classes (File, FileList, About, ...) are JSON-mapped reflectively.
-keep class com.google.api.services.drive.model.** { *; }

# Optional transports/integrations google-http-client references but the app doesn't use.
-dontwarn com.google.appengine.api.**
-dontwarn com.google.apphosting.api.**
-dontwarn com.google.common.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn com.google.j2objc.annotations.**
-dontwarn javax.annotation.**
-dontwarn org.apache.http.**
-dontwarn org.checkerframework.**
-dontwarn org.codehaus.mojo.animal_sniffer.**
-dontwarn com.sun.net.httpserver.**
-dontwarn io.grpc.**
-dontwarn io.opencensus.**

# ---- kotlinx.serialization (type-safe navigation routes) ----
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclasseswithmembers class ** {
    kotlinx.serialization.KSerializer serializer(...);
}
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1>$Companion {
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclassmembers class * {
    @kotlinx.serialization.SerialName <fields>;
}
-dontnote kotlinx.serialization.**
