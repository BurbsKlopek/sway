# Keep Gson models & domain serialization intact under R8
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes EnclosingMethod
-keepattributes InnerClasses

-keep class com.burbsklopek.flickdeck.model.** { *; }
-keep class com.burbsklopek.flickdeck.data.** { *; }
-keep class com.google.gson.** { *; }
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn com.google.zxing.**
