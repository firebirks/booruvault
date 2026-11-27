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

# GeckoView's WebAuthn support calls Google Play services' FIDO library, which the app leaves out:
# it ships nothing of Google's. A page reaching it would take the app down: the engine's WebAuthn
# is turned off by a preference (GeckoPrefs).
-dontwarn com.google.android.gms.**

# GeckoView reads its first preferences (GeckoPrefs) from a YAML file with SnakeYAML, by
# reflection. Shrunk and moved out of its package, SnakeYAML's TypeDescription asks in its static
# initializer for a package it no longer has, and the engine's start throws an Error GeckoView
# does not catch: the app ended as the first browser opened. Kept whole, under its own names.
-keep class org.yaml.snakeyaml.** { *; }
-dontwarn java.beans.**

# No verbose, debug or info message leaves the release build for the system log, GeckoView's
# included: GeckoSession logs the address of every page it loads.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
