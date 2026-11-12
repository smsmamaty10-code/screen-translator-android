package com.screentranslator.capacitor;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.nl.translate.TranslateLanguage;
import com.google.mlkit.nl.translate.Translation;
import com.google.mlkit.nl.translate.Translator;
import com.google.mlkit.nl.translate.TranslatorOptions;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import org.json.JSONArray;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class FloatingTranslatorService extends Service {

    private static FloatingTranslatorService activeInstance;
    private static String currentSourceLang = "auto";
    private static String currentTargetLang = "ar";

    private WindowManager windowManager;
    private FrameLayout miniBubbleContainer;
    private TextView bubbleIconTv;
    private ProgressBar bubbleSpinner;

    private FrameLayout fullScreenOverlayContainer;
    private LensStyleOverlayView lensOverlayView;

    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private TextRecognizer textRecognizer;
    private final ExecutorService executor = Executors.newFixedThreadPool(3);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private int screenWidth = 1080;
    private int screenHeight = 2340;
    private int screenDensity = 420;
    private boolean overlayShowing = false;

    public static void updateLanguages(String src, String tgt) {
        if (src != null) currentSourceLang = src;
        if (tgt != null) currentTargetLang = tgt;
    }

    public static void showTranslatedPopupOnScreen(String text) {
        // Handled directly in-place by LensStyleOverlayView
    }

    private int dp(int value) {
        return (int) TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value,
            getResources().getDisplayMetrics()
        );
    }

    @Override
    public void onCreate() {
        super.onCreate();
        activeInstance = this;
        windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);

        DisplayMetrics dm = new DisplayMetrics();
        windowManager.getDefaultDisplay().getRealMetrics(dm);
        screenWidth = dm.widthPixels;
        screenHeight = dm.heightPixels;
        screenDensity = dm.densityDpi;

        startForegroundWithNotification();
        createFullScreenLensOverlay();
        createCompactFloatingBubble();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            int resultCode = intent.getIntExtra("resultCode", Activity.RESULT_CANCELED);
            Intent resultData = intent.getParcelableExtra("resultData");
            String src = intent.getStringExtra("sourceLang");
            String tgt = intent.getStringExtra("targetLang");
            if (src != null) currentSourceLang = src;
            if (tgt != null) currentTargetLang = tgt;

            if (resultData != null && mediaProjection == null) {
                setupMediaProjection(resultCode, resultData);
            }
        }
        return START_STICKY;
    }

    private void startForegroundWithNotification() {
        String channelId = "lens_screen_translator_channel";
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel chan = new NotificationChannel(
                channelId,
                "عدسة مترجم الشاشة الفوري",
                NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.createNotificationChannel(chan);
            builder = new Notification.Builder(this, channelId);
        } else {
            builder = new Notification.Builder(this);
        }

        Notification notification = builder
            .setContentTitle("عدسة الترجمة الفورية جاهزة")
            .setContentText("اضغط الزر الدائري الصغير لترجمة الشاشة في مكانها (ضغطة مطولة للإغلاق)")
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setOngoing(true)
            .build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(101, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(101, notification);
        }
    }

    private void setupMediaProjection(int resultCode, Intent resultData) {
        MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        if (mpm == null) return;
        mediaProjection = mpm.getMediaProjection(resultCode, resultData);
        if (mediaProjection == null) return;

        mediaProjection.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                releaseProjection();
            }
        }, mainHandler);

        imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2);
        virtualDisplay = mediaProjection.createVirtualDisplay(
            "LensScreenTranslator",
            screenWidth,
            screenHeight,
            screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader.getSurface(),
            null,
            null
        );
    }

    /**
     * طبقة عدسة جوجل بملء الشاشة — ترسم كل نص مترجم في مكانه الأصلي بالضبط، وتختفي بمجرد لمس الشاشة!
     */
    private void createFullScreenLensOverlay() {
        fullScreenOverlayContainer = new FrameLayout(this);
        lensOverlayView = new LensStyleOverlayView(this);
        lensOverlayView.setOnDismissTapListener(this::hideLensOverlay);

        fullScreenOverlayContainer.addView(lensOverlayView, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ));

        // Small subtle pill at bottom to indicate how to dismiss
        TextView hintChip = new TextView(this);
        hintChip.setText("✕ المس أي مكان لإخفاء الترجمة ومتابعة التصفح");
        hintChip.setTextColor(Color.WHITE);
        hintChip.setTextSize(11.5f);
        hintChip.setPadding(dp(14), dp(6), dp(14), dp(6));
        GradientDrawable chipBg = new GradientDrawable();
        chipBg.setColor(Color.parseColor("#CC0F172A"));
        chipBg.setCornerRadius(dp(20));
        chipBg.setStroke(dp(1), Color.parseColor("#38BDF8"));
        hintChip.setBackground(chipBg);
        hintChip.setOnClickListener(v -> hideLensOverlay());

        FrameLayout.LayoutParams chipLp = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        );
        chipLp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        chipLp.bottomMargin = dp(38);
        fullScreenOverlayContainer.addView(hintChip, chipLp);

        int overlayType = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            : WindowManager.LayoutParams.TYPE_PHONE;

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        );
        lp.gravity = Gravity.TOP | Gravity.START;

        fullScreenOverlayContainer.setVisibility(View.GONE);
        windowManager.addView(fullScreenOverlayContainer, lp);
    }

    /**
     * أيقونة دائرية صغيرة جداً وأنيقة (44dp فقط!) قابلة للسحب:
     * - ضغطة واحدة: تترجم الشاشة فوراً مثل عدسة جوجل (أو تخفي الترجمة إذا كانت ظاهرة)
     * - ضغطة مطولة: تغلق الزر العائم
     */
    private void createCompactFloatingBubble() {
        int sizePx = dp(36);
        miniBubbleContainer = new FrameLayout(this);

        GradientDrawable circleBg = new GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            new int[]{Color.parseColor("#E60284C7"), Color.parseColor("#E64F46E5")}
        );
        circleBg.setShape(GradientDrawable.OVAL);
        circleBg.setStroke(dp(2), Color.parseColor("#7DD3FC"));
        miniBubbleContainer.setBackground(circleBg);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            miniBubbleContainer.setElevation(dp(8));
        }

        bubbleIconTv = new TextView(this);
        bubbleIconTv.setText("تر");
        bubbleIconTv.setTextColor(Color.WHITE);
        bubbleIconTv.setTextSize(11.5f);
        bubbleIconTv.getPaint().setFakeBoldText(true);
        bubbleIconTv.setGravity(Gravity.CENTER);

        bubbleSpinner = new ProgressBar(this);
        bubbleSpinner.setVisibility(View.GONE);
        int pad = dp(8);
        bubbleSpinner.setPadding(pad, pad, pad, pad);

        miniBubbleContainer.addView(bubbleIconTv, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ));
        miniBubbleContainer.addView(bubbleSpinner, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ));

        int overlayType = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            : WindowManager.LayoutParams.TYPE_PHONE;

        final WindowManager.LayoutParams params = new WindowManager.LayoutParams(
            sizePx,
            sizePx,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = screenWidth - sizePx - dp(12);
        params.y = screenHeight / 3;

        miniBubbleContainer.setOnTouchListener(new View.OnTouchListener() {
            private int initX, initY;
            private float downRawX, downRawY;
            private long downTime;
            private boolean moved;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        initX = params.x;
                        initY = params.y;
                        downRawX = event.getRawX();
                        downRawY = event.getRawY();
                        downTime = System.currentTimeMillis();
                        moved = false;
                        miniBubbleContainer.setAlpha(1.0f);
                        return true;

                    case MotionEvent.ACTION_MOVE:
                        float dx = event.getRawX() - downRawX;
                        float dy = event.getRawY() - downRawY;
                        if (Math.hypot(dx, dy) > dp(6)) {
                            moved = true;
                            params.x = Math.max(0, Math.min(screenWidth - sizePx, initX + (int) dx));
                            params.y = Math.max(dp(24), Math.min(screenHeight - sizePx - dp(24), initY + (int) dy));
                            windowManager.updateViewLayout(miniBubbleContainer, params);
                        }
                        return true;

                    case MotionEvent.ACTION_UP:
                        long pressDuration = System.currentTimeMillis() - downTime;
                        if (!moved) {
                            if (pressDuration > 750) {
                                Toast.makeText(FloatingTranslatorService.this, "تم إغلاق عدسة الترجمة العائمة", Toast.LENGTH_SHORT).show();
                                stopSelf();
                            } else {
                                if (overlayShowing) {
                                    hideLensOverlay();
                                } else {
                                    triggerInPlaceLensTranslation();
                                }
                            }
                        } else {
                            // Snap neatly to nearest screen edge so it never blocks content
                            params.x = (params.x + sizePx / 2 < screenWidth / 2) ? dp(8) : (screenWidth - sizePx - dp(8));
                            windowManager.updateViewLayout(miniBubbleContainer, params);
                            miniBubbleContainer.setAlpha(0.85f);
                        }
                        return true;
                }
                return false;
            }
        });

        miniBubbleContainer.setAlpha(0.85f);
        windowManager.addView(miniBubbleContainer, params);
    }

    private void hideLensOverlay() {
        overlayShowing = false;
        if (lensOverlayView != null) lensOverlayView.clearItems();
        if (fullScreenOverlayContainer != null) fullScreenOverlayContainer.setVisibility(View.GONE);
        if (miniBubbleContainer != null) {
            bubbleIconTv.setText("تر");
            miniBubbleContainer.setAlpha(0.85f);
        }
    }

    private void triggerInPlaceLensTranslation() {
        if (miniBubbleContainer == null) return;
        hideLensOverlay();

        // Hide the tiny bubble for 140ms so it doesn't appear in the screenshot
        miniBubbleContainer.setVisibility(View.INVISIBLE);

        mainHandler.postDelayed(() -> {
            final Bitmap screenBmp = grabScreenBitmap();
            miniBubbleContainer.setVisibility(View.VISIBLE);

            if (screenBmp == null) {
                Toast.makeText(this, "جاري تحديث الشاشة، اضغط مرة أخرى", Toast.LENGTH_SHORT).show();
                return;
            }

            bubbleIconTv.setVisibility(View.GONE);
            bubbleSpinner.setVisibility(View.VISIBLE);

            InputImage inputImage = InputImage.fromBitmap(screenBmp, 0);
            textRecognizer.process(inputImage)
                .addOnSuccessListener(visionText -> {
                    executor.execute(() -> processDetectedLinesInPlace(screenBmp, visionText));
                })
                .addOnFailureListener(e -> {
                    screenBmp.recycle();
                    resetBubbleState();
                    Toast.makeText(this, "تعذر قراءة الشاشة", Toast.LENGTH_SHORT).show();
                });
        }, 140);
    }

    private void processDetectedLinesInPlace(Bitmap screenBmp, Text visionText) {
        List<Rect> lineRects = new ArrayList<>();
        List<String> lineStrings = new ArrayList<>();
        List<Integer> bgColors = new ArrayList<>();
        List<Integer> txtColors = new ArrayList<>();

        for (Text.TextBlock block : visionText.getTextBlocks()) {
            for (Text.Line line : block.getLines()) {
                String raw = line.getText() != null ? line.getText().trim() : "";
                Rect box = line.getBoundingBox();
                if (raw.length() < 2 || box == null) continue;

                // Sample background pixel right around the text box so it blends seamlessly like Google Lens!
                int sampledBg = sampleBackgroundColorAroundRect(screenBmp, box);
                int luminance = (int) (0.299 * Color.red(sampledBg) + 0.587 * Color.green(sampledBg) + 0.114 * Color.blue(sampledBg));
                int bgWithAlpha = Color.argb(238, Color.red(sampledBg), Color.green(sampledBg), Color.blue(sampledBg));
                int contrastText = (luminance > 145) ? Color.parseColor("#0F172A") : Color.parseColor("#F8FAFC");

                lineRects.add(box);
                lineStrings.add(raw);
                bgColors.add(bgWithAlpha);
                txtColors.add(contrastText);
            }
        }
        screenBmp.recycle();

        if (lineStrings.isEmpty()) {
            mainHandler.post(() -> {
                resetBubbleState();
                Toast.makeText(this, "لا توجد نصوص واضحة على هذه الشاشة", Toast.LENGTH_SHORT).show();
            });
            return;
        }

        // Batch-translate all lines at once for instant Google Lens speed (<400ms)!
        List<String> translatedLines = batchTranslateLines(lineStrings, currentSourceLang, currentTargetLang);

        final List<LensStyleOverlayView.OverlayItem> overlayItems = new ArrayList<>();
        for (int i = 0; i < lineRects.size(); i++) {
            String tr = (i < translatedLines.size()) ? translatedLines.get(i) : lineStrings.get(i);
            overlayItems.add(new LensStyleOverlayView.OverlayItem(
                lineRects.get(i),
                tr,
                bgColors.get(i),
                txtColors.get(i)
            ));
        }

        mainHandler.post(() -> {
            resetBubbleState();
            bubbleIconTv.setText("✕");
            lensOverlayView.setItems(overlayItems);
            fullScreenOverlayContainer.setVisibility(View.VISIBLE);
            overlayShowing = true;
        });
    }

    private int sampleBackgroundColorAroundRect(Bitmap bmp, Rect r) {
        try {
            int w = bmp.getWidth();
            int h = bmp.getHeight();
            int sx1 = Math.max(0, Math.min(w - 1, r.left - 4));
            int sy1 = Math.max(0, Math.min(h - 1, r.top - 4));
            int sx2 = Math.max(0, Math.min(w - 1, r.right + 4));
            int sy2 = Math.max(0, Math.min(h - 1, r.bottom + 4));

            int c1 = bmp.getPixel(sx1, sy1);
            int c2 = bmp.getPixel(sx2, sy1);
            int c3 = bmp.getPixel(sx1, sy2);
            int c4 = bmp.getPixel(sx2, sy2);

            int red = (Color.red(c1) + Color.red(c2) + Color.red(c3) + Color.red(c4)) / 4;
            int green = (Color.green(c1) + Color.green(c2) + Color.green(c3) + Color.green(c4)) / 4;
            int blue = (Color.blue(c1) + Color.blue(c2) + Color.blue(c3) + Color.blue(c4)) / 4;

            // Slightly lighten dark pills like Google Lens gray pills (#37393D) for clear readability
            if (red < 35 && green < 35 && blue < 40) {
                return Color.rgb(54, 58, 66);
            }
            return Color.rgb(red, green, blue);
        } catch (Exception e) {
            return Color.rgb(54, 58, 66);
        }
    }

    private List<String> batchTranslateLines(List<String> lines, String sl, String tl) {
        String separator = " ||| ";
        StringBuilder joined = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) joined.append("\n");
            joined.append(lines.get(i));
        }

        try {
            String srcParam = (sl == null || sl.isEmpty()) ? "auto" : sl;
            String urlStr = "https://translate.googleapis.com/translate_a/single?client=gtx&sl="
                + URLEncoder.encode(srcParam, "UTF-8")
                + "&tl=" + URLEncoder.encode(tl, "UTF-8")
                + "&dt=t&q=" + URLEncoder.encode(joined.toString(), "UTF-8");

            HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setConnectTimeout(4500);
            conn.setReadTimeout(5000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0");

            if (conn.getResponseCode() == 200) {
                BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
                StringBuilder resp = new StringBuilder();
                String ln;
                while ((ln = br.readLine()) != null) resp.append(ln);
                br.close();

                JSONArray outer = new JSONArray(resp.toString());
                JSONArray parts = outer.getJSONArray(0);
                StringBuilder fullTranslated = new StringBuilder();
                for (int i = 0; i < parts.length(); i++) {
                    JSONArray seg = parts.getJSONArray(i);
                    if (!seg.isNull(0)) fullTranslated.append(seg.getString(0));
                }

                String[] split = fullTranslated.toString().split("\r?\n");
                List<String> result = new ArrayList<>();
                for (int i = 0; i < lines.size(); i++) {
                    result.add(i < split.length ? split[i].trim() : lines.get(i));
                }
                return result;
            }
        } catch (Exception ignored) {
        }

        // Offline fallback via ML Kit On-Device Translator if internet is unavailable
        List<String> fallback = new ArrayList<>();
        String mlSrc = TranslateLanguage.fromLanguageTag("auto".equals(sl) ? "en" : sl);
        String mlTgt = TranslateLanguage.fromLanguageTag(tl);
        if (mlSrc == null) mlSrc = TranslateLanguage.ENGLISH;
        if (mlTgt == null) mlTgt = TranslateLanguage.ARABIC;

        TranslatorOptions opts = new TranslatorOptions.Builder()
            .setSourceLanguage(mlSrc)
            .setTargetLanguage(mlTgt)
            .build();
        Translator translator = Translation.getClient(opts);
        try {
            com.google.android.gms.tasks.Tasks.await(translator.downloadModelIfNeeded(new DownloadConditions.Builder().build()));
            for (String s : lines) {
                String tr = com.google.android.gms.tasks.Tasks.await(translator.translate(s));
                fallback.add(tr);
            }
        } catch (Exception e) {
            fallback.addAll(lines);
        } finally {
            translator.close();
        }
        return fallback;
    }

    private void resetBubbleState() {
        if (bubbleSpinner != null) bubbleSpinner.setVisibility(View.GONE);
        if (bubbleIconTv != null) bubbleIconTv.setVisibility(View.VISIBLE);
    }

    private Bitmap grabScreenBitmap() {
        if (imageReader == null) return null;
        Image image = imageReader.acquireLatestImage();
        if (image == null) return null;
        try {
            Image.Plane[] planes = image.getPlanes();
            ByteBuffer buffer = planes[0].getBuffer();
            int pixelStride = planes[0].getPixelStride();
            int rowStride = planes[0].getRowStride();
            int rowPadding = rowStride - pixelStride * screenWidth;

            Bitmap full = Bitmap.createBitmap(
                screenWidth + rowPadding / pixelStride,
                screenHeight,
                Bitmap.Config.ARGB_8888
            );
            full.copyPixelsFromBuffer(buffer);
            Bitmap cropped = Bitmap.createBitmap(full, 0, 0, screenWidth, screenHeight);
            if (cropped != full) full.recycle();
            return cropped;
        } catch (Exception e) {
            return null;
        } finally {
            image.close();
        }
    }

    private void releaseProjection() {
        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }
        if (imageReader != null) {
            imageReader.close();
            imageReader = null;
        }
    }

    @Override
    public void onDestroy() {
        activeInstance = null;
        releaseProjection();
        if (mediaProjection != null) {
            mediaProjection.stop();
            mediaProjection = null;
        }
        if (textRecognizer != null) {
            textRecognizer.close();
        }
        executor.shutdownNow();
        if (miniBubbleContainer != null && windowManager != null) {
            windowManager.removeView(miniBubbleContainer);
        }
        if (fullScreenOverlayContainer != null && windowManager != null) {
            windowManager.removeView(fullScreenOverlayContainer);
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
