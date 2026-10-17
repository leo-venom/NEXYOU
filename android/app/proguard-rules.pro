# NEXYOU — regras R8

# Ponte JS <-> WebView: metodos anotados nunca sao removidos/renomeados
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keepattributes *Annotation*

# NewPipe Extractor (uso extenso de internals)
-keep class org.schabi.newpipe.extractor.** { *; }
-dontwarn org.schabi.newpipe.extractor.**

# Rhino (avaliador JS do YouTube dentro do NPE)
-keep class org.mozilla.javascript.** { *; }
-dontwarn org.mozilla.javascript.**

# protobuf-javalite (transitivo do NPE)
-keep class com.google.protobuf.** { *; }
-dontwarn com.google.protobuf.**

# Dependencias opcionais/annotations ausentes no runtime Android
-dontwarn javax.annotation.**
-dontwarn javax.script.**
-dontwarn org.jsoup.**
