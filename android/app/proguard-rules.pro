-keep class org.twofold.app.** extends android.content.ContentProvider { *; }

# Keep connector diagnostics from logging registration capabilities in release.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
    public static int wtf(...);
}
