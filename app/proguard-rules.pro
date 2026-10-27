# kotlinx.serialization: keep generated serializers of @Serializable classes.
-keepattributes *Annotation*, InnerClasses
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    *** Companion;
    static ** INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclasseswithmembers class ** {
    kotlinx.serialization.KSerializer serializer(...);
}

# BouncyCastle is used only through its lightweight Argon2 API; the provider classes it ships
# reference JDK-only types.
-dontwarn org.bouncycastle.**
-dontwarn javax.naming.**

# jsoup can use re2j for regex selectors when present; it is not.
-dontwarn com.google.re2j.**
-dontwarn org.jspecify.**
