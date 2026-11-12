# الحفاظ على جسور JavaScript و Capacitor و Google AdMob أثناء ضغط الكود وتوليد ملف mapping.txt
-keepattributes *Annotation*,JavascriptInterface
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keep class com.getcapacitor.** { *; }
-keep class com.google.android.gms.ads.** { *; }
