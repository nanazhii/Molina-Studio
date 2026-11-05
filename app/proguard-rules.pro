# Molina Studio: aturan R8 untuk rilis. Aturan konsumen modul (mis. feature/mpv: MPVLib) ikut berlaku.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# JNI PTY Terminal: native mencari simbol Java_com_molina_terminal_JNI_*
-keep class com.molina.terminal.JNI { native <methods>; }
-keepclasseswithmembernames,includedescriptorclasses class * { native <methods>; }

# Cinema: fragment dan Preference dipanggil lewat nama kelas di XML preferensi
-keep class com.molina.cinema.preferences.** { *; }
-keepclassmembers class * extends android.view.View { <init>(android.content.Context, android.util.AttributeSet); }

# NewPipeExtractor (refleksi, Rhino, protobuf-lite)
-keep class org.schabi.newpipe.extractor.timeago.patterns.** { *; }
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.classfile.ClassFileWriter
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite { <fields>; }
-dontwarn org.mozilla.javascript.JavaToJSONConverters
-dontwarn org.mozilla.javascript.tools.**
-dontwarn java.beans.**
-dontwarn javax.script.**
-dontwarn jdk.dynalink.**
-dontwarn edu.umd.cs.findbugs.annotations.**

# commons-compress: ServiceLoader dan kompresor opsional yang tidak disertakan
-keep class org.apache.commons.compress.** { *; }
-dontwarn org.apache.commons.compress.**
-dontwarn com.github.luben.zstd.**
-dontwarn org.brotli.dec.**
-dontwarn org.objectweb.asm.**

# [MOLINA-HARDEN] Rilis: buang Log.v dan pesan null-check Kotlin (nama parameter internal).
# Log.d/i/w/e sengaja dipertahankan agar Logcat Reporter tetap berisi.
# [R8-OPTIMIZE] Efektif karena app memakai proguard-android-optimize.txt (optimasi R8 aktif).
# -dontobfuscate dari modul mpv sengaja dipertahankan: CrashReporter menulis jejak tumpukan di perangkat,
# dan GPL terbuka tidak butuh penyamaran nama.
-assumenosideeffects class android.util.Log {
    public static int v(...);
}
-assumenosideeffects class kotlin.jvm.internal.Intrinsics {
    public static void checkNotNull(java.lang.Object, java.lang.String);
    public static void checkNotNullParameter(java.lang.Object, java.lang.String);
    public static void checkNotNullExpressionValue(java.lang.Object, java.lang.String);
}
