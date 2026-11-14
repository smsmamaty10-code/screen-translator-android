package com.screentranslator.capacitor;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.speech.tts.TextToSpeech;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;
import org.json.JSONObject;
import java.util.Locale;

public class MainActivity extends Activity {

    private static final int REQ_OVERLAY_PERMISSION = 2001;
    private static final int REQ_MEDIA_PROJECTION = 2002;
    private static final int REQ_FILE_CHOOSER = 2003;

    public static MainActivity instance;
    private WebView webView;
    private ValueCallback<Uri[]> filePathCallback;
    private TextToSpeech tts;
    private String pendingSourceLang = "auto";
    private String pendingTargetLang = "ar";

    public static void closeEntireApp() {
        if (instance != null) {
            instance.runOnUiThread(() -> {
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        instance.finishAndRemoveTask();
                    } else {
                        instance.finishAffinity();
                    }
                } catch (Exception e) {
                    instance.finish();
                }
            });
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        instance = this;

        tts = new TextToSpeech(this, status -> {});

        webView = new WebView(this);
        setContentView(webView);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setMediaPlaybackRequiresUserGesture(false);

        webView.addJavascriptInterface(new AndroidBridge(), "AndroidBridge");
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                TranslationStyleConfig cfg = TranslationStyleConfig.load(MainActivity.this);
                notifyStyleChangedFromService(cfg.toJsonString());
            }
        });
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (filePathCallback != null) {
                    filePathCallback.onReceiveValue(null);
                }
                filePathCallback = callback;
                Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("image/*");
                startActivityForResult(Intent.createChooser(intent, "اختر لقطة شاشة"), REQ_FILE_CHOOSER);
                return true;
            }
        });

        webView.loadUrl("file:///android_asset/public/index.html");
    }

    @Override
    protected void onResume() {
        super.onResume();
        TranslationStyleConfig cfg = TranslationStyleConfig.load(this);
        notifyStyleChangedFromService(cfg.toJsonString());
    }

    public void notifyStyleChangedFromService(final String styleJson) {
        runOnUiThread(() -> {
            if (webView != null && styleJson != null) {
                String quoted = JSONObject.quote(styleJson);
                webView.evaluateJavascript("if(window.onNativeStyleSynced){window.onNativeStyleSynced(" + quoted + ");}", null);
            }
        });
    }

    public void deliverCapturedScreenToWebView(final String base64DataUrl) {
        runOnUiThread(() -> {
            if (webView != null) {
                String safe = base64DataUrl.replace("\n", "").replace("\r", "");
                webView.evaluateJavascript("if(window.onNativeScreenCaptured){window.onNativeScreenCaptured('" + safe + "');}", null);
            }
        });
    }

    public class AndroidBridge {
        @JavascriptInterface
        public void startFloatingTranslator(String sourceLang, String targetLang) {
            pendingSourceLang = sourceLang != null ? sourceLang : "auto";
            pendingTargetLang = targetLang != null ? targetLang : "ar";
            runOnUiThread(() -> checkOverlayAndStartProjection());
        }

        @JavascriptInterface
        public void stopFloatingTranslator() {
            runOnUiThread(() -> {
                Intent stopIntent = new Intent(MainActivity.this, FloatingTranslatorService.class);
                stopService(stopIntent);
            });
        }

        @JavascriptInterface
        public void closeEntireAppFromWeb() {
            runOnUiThread(() -> {
                Intent stopIntent = new Intent(MainActivity.this, FloatingTranslatorService.class);
                stopService(stopIntent);
                Toast.makeText(MainActivity.this, "تم إيقاف التطبيق بالكامل", Toast.LENGTH_SHORT).show();
                closeEntireApp();
            });
        }

        @JavascriptInterface
        public void updateLanguages(String sourceLang, String targetLang) {
            pendingSourceLang = sourceLang != null ? sourceLang : "auto";
            pendingTargetLang = targetLang != null ? targetLang : "ar";
            FloatingTranslatorService.updateLanguages(pendingSourceLang, pendingTargetLang);
        }

        @JavascriptInterface
        public void updateTranslationStyle(String styleJson) {
            TranslationStyleConfig.fromJsonAndSave(MainActivity.this, styleJson);
            FloatingTranslatorService.reloadStyleFromPrefs(MainActivity.this);
        }

        @JavascriptInterface
        public String getTranslationStyleJson() {
            return TranslationStyleConfig.load(MainActivity.this).toJsonString();
        }

        @JavascriptInterface
        public void showOverlayTranslationResult(String translatedSummary) {
            runOnUiThread(() -> FloatingTranslatorService.showTranslatedPopupOnScreen(translatedSummary));
        }

        @JavascriptInterface
        public void copyText(String text) {
            runOnUiThread(() -> {
                ClipboardManager cb = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (cb != null) {
                    cb.setPrimaryClip(ClipData.newPlainText("translation", text));
                }
            });
        }

        @JavascriptInterface
        public void speakText(String text, String langCode) {
            runOnUiThread(() -> {
                if (tts != null) {
                    tts.setLanguage(new Locale(langCode != null ? langCode : "ar"));
                    tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "utt1");
                }
            });
        }
    }

    private void checkOverlayAndStartProjection() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "يرجى تفعيل إذن (الظهور فوق التطبيقات) أولاً لتشغيل الزر العائم", Toast.LENGTH_LONG).show();
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()));
            startActivityForResult(intent, REQ_OVERLAY_PERMISSION);
            return;
        }
        requestMediaProjection();
    }

    private void requestMediaProjection() {
        MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        if (mpm != null) {
            startActivityForResult(mpm.createScreenCaptureIntent(), REQ_MEDIA_PROJECTION);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_OVERLAY_PERMISSION) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)) {
                requestMediaProjection();
            } else {
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(MainActivity.this)) {
                        requestMediaProjection();
                    } else {
                        Toast.makeText(MainActivity.this, "يرجى تفعيل مفتاح السماح بالظهور فوق التطبيقات", Toast.LENGTH_LONG).show();
                    }
                }, 400);
            }
        } else if (requestCode == REQ_MEDIA_PROJECTION) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                Intent serviceIntent = new Intent(this, FloatingTranslatorService.class);
                serviceIntent.putExtra("resultCode", resultCode);
                serviceIntent.putExtra("resultData", data);
                serviceIntent.putExtra("sourceLang", pendingSourceLang);
                serviceIntent.putExtra("targetLang", pendingTargetLang);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent);
                } else {
                    startService(serviceIntent);
                }
                Toast.makeText(this, "بدأ الزر العائم v5! اضغط (تر) للترجمة أو (✕⚙) للتخصيص وإيقاف التطبيق", Toast.LENGTH_LONG).show();
                moveTaskToBack(true);
            } else {
                Toast.makeText(this, "تم إلغاء إذن التقاط الشاشة", Toast.LENGTH_SHORT).show();
            }
        } else if (requestCode == REQ_FILE_CHOOSER) {
            if (filePathCallback != null) {
                Uri[] results = null;
                if (resultCode == Activity.RESULT_OK && data != null && data.getDataString() != null) {
                    results = new Uri[]{Uri.parse(data.getDataString())};
                }
                filePathCallback.onReceiveValue(results);
                filePathCallback = null;
            }
        }
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        if (instance == this) {
            instance = null;
        }
        if (tts != null) {
            tts.shutdown();
        }
        super.onDestroy();
    }
}
