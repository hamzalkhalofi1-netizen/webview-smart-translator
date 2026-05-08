# ── ML Kit: Text Recognition ─────────────────────────────────────────────────
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_text_common.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_text.** { *; }
-dontwarn com.google.mlkit.**

# ── ML Kit: Translation ───────────────────────────────────────────────────────
-keep class com.google.android.gms.internal.mlkit_translate.** { *; }
-dontwarn com.google.android.gms.internal.mlkit_translate.**

# ── ML Kit: Common ────────────────────────────────────────────────────────────
-keep class com.google.android.gms.tasks.** { *; }
-keep class com.google.firebase.** { *; }
-dontwarn com.google.firebase.**

# ── AndroidX ──────────────────────────────────────────────────────────────────
-keep class androidx.viewbinding.** { *; }
-keep class * implements androidx.viewbinding.ViewBinding { *; }

# ── App classes ───────────────────────────────────────────────────────────────
-keep class com.translator.webview.** { *; }

# ── General ───────────────────────────────────────────────────────────────────
-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable
-dontobfuscate
