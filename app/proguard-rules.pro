# Shrink but do not rename. A crash on a test build is read out of the app itself, and an
# obfuscated stack trace turns that back into guesswork. The source is public anyway.
-dontobfuscate
-keepattributes SourceFile,LineNumberTable

# The globe page calls these by name through the JavaScript bridge.
-keepclassmembers class com.verisonder.sondereye.ui.GlobeBridge {
    @android.webkit.JavascriptInterface <methods>;
}
