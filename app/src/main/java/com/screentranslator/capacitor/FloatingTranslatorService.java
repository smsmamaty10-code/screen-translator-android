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
import android.util.Base64;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

public class FloatingTranslatorService extends Service {

    private static FloatingTranslatorService activeInstance;
    private static String currentSourceLang = "auto";
    private static String currentTargetLang = "ar";

    private WindowManager windowManager;
    private LinearLayout floatingPillLayout;
    private LinearLayout resultOverlayLayout;
    private TextView resultTextView;

    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private int screenWidth = 1080;
    private int screenHeight = 1920;
    private int screenDensity = 420;

    public static void updateLanguages(String src, String tgt) {
        currentSourceLang = src;
        currentTargetLang = tgt;
    }

    public static void showTranslatedPopupOnScreen(String text) {
        if (activeInstance != null) {
            activeInstance.displayResultOverlay(text);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        activeInstance = this;
        windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        DisplayMetrics dm = getResources().getDisplayMetrics();
        screenWidth = dm.widthPixels;
        screenHeight = dm.heightPixels;
        screenDensity = dm.densityDpi;

        startForegroundWithNotification();
        createFloatingPill();
        createResultOverlayWindow();
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
        String channelId = "screen_translator_overlay_channel";
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel chan = new NotificationChannel(
                channelId,
                "خدمة مترجم الشاشة العائم",
                NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.createNotificationChannel(chan);
            builder = new Notification.Builder(this, channelId);
        } else {
            builder = new Notification.Builder(this);
        }

        Notification notification = builder
            .setContentTitle("مترجم الشاشة العائم نشط")
            .setContentText("اضغط على الزر العائم لترجمة أي شاشة أمامك")
            .setSmallIcon(android.R.drawable.ic_menu_sort_alphabetically)
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
        }, new Handler(Looper.getMainLooper()));

        imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2);
        virtualDisplay = mediaProjection.createVirtualDisplay(
            "ScreenTranslatorDisplay",
            screenWidth,
            screenHeight,
            screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader.getSurface(),
            null,
            null
        );
    }

    private void createFloatingPill() {
        floatingPillLayout = new LinearLayout(this);
        floatingPillLayout.setOrientation(LinearLayout.HORIZONTAL);
        floatingPillLayout.setGravity(Gravity.CENTER_VERTICAL);
        floatingPillLayout.setPadding(24, 16, 24, 16);

        GradientDrawable pillBg = new GradientDrawable();
        pillBg.setColor(Color.parseColor("#EB0F172A"));
        pillBg.setCornerRadius(48f);
        pillBg.setStroke(3, Color.parseColor("#38BDF8"));
        floatingPillLayout.setBackground(pillBg);

        TextView dragHandle = new TextView(this);
        dragHandle.setText("⠿ اسحب  ");
        dragHandle.setTextColor(Color.parseColor("#7DD3FC"));
        dragHandle.setTextSize(13f);

        Button translateBtn = new Button(this);
        translateBtn.setText("⚡ ترجم الشاشة");
        translateBtn.setTextColor(Color.WHITE);
        translateBtn.setTextSize(13f);
        GradientDrawable btnBg = new GradientDrawable();
        btnBg.setColor(Color.parseColor("#0284C7"));
        btnBg.setCornerRadius(32f);
        translateBtn.setBackground(btnBg);
        translateBtn.setPadding(28, 10, 28, 10);

        Button openAppBtn = new Button(this);
        openAppBtn.setText("📋");
        openAppBtn.setTextColor(Color.WHITE);
        openAppBtn.setTextSize(13f);
        openAppBtn.setBackgroundColor(Color.TRANSPARENT);
        openAppBtn.setPadding(16, 10, 16, 10);

        Button closeBtn = new Button(this);
        closeBtn.setText("✕");
        closeBtn.setTextColor(Color.parseColor("#FCA5A5"));
        closeBtn.setTextSize(14f);
        closeBtn.setBackgroundColor(Color.TRANSPARENT);
        closeBtn.setPadding(16, 10, 16, 10);

        floatingPillLayout.addView(dragHandle);
        floatingPillLayout.addView(translateBtn);
        floatingPillLayout.addView(openAppBtn);
        floatingPillLayout.addView(closeBtn);

        int overlayType = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            : WindowManager.LayoutParams.TYPE_PHONE;

        final WindowManager.LayoutParams params = new WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = 30;
        params.y = 220;

        dragHandle.setOnTouchListener(new View.OnTouchListener() {
            private int initX, initY;
            private float touchX, touchY;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        initX = params.x;
                        initY = params.y;
                        touchX = event.getRawX();
                        touchY = event.getRawY();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        params.x = initX + (int) (event.getRawX() - touchX);
                        params.y = initY + (int) (event.getRawY() - touchY);
                        windowManager.updateViewLayout(floatingPillLayout, params);
                        return true;
                }
                return false;
            }
        });

        translateBtn.setOnClickListener(v -> captureScreenAndTranslate());

        openAppBtn.setOnClickListener(v -> {
            Intent i = new Intent(FloatingTranslatorService.this, MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(i);
        });

        closeBtn.setOnClickListener(v -> stopSelf());

        windowManager.addView(floatingPillLayout, params);
    }

    private void createResultOverlayWindow() {
        resultOverlayLayout = new LinearLayout(this);
        resultOverlayLayout.setOrientation(LinearLayout.VERTICAL);
        resultOverlayLayout.setPadding(32, 28, 32, 28);

        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(Color.parseColor("#F2090E1A"));
        cardBg.setCornerRadius(36f);
        cardBg.setStroke(4, Color.parseColor("#38BDF8"));
        resultOverlayLayout.setBackground(cardBg);

        TextView headerTv = new TextView(this);
        headerTv.setText("🌐 ترجمة الشاشة الفورية (" + currentSourceLang.toUpperCase() + " ➔ " + currentTargetLang.toUpperCase() + ")");
        headerTv.setTextColor(Color.parseColor("#38BDF8"));
        headerTv.setTextSize(15f);
        headerTv.setPadding(0, 0, 0, 14);

        ScrollView scroll = new ScrollView(this);
        resultTextView = new TextView(this);
        resultTextView.setTextColor(Color.WHITE);
        resultTextView.setTextSize(16f);
        scroll.addView(resultTextView);

        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            420
        );
        scroll.setLayoutParams(scrollLp);

        Button hideBtn = new Button(this);
        hideBtn.setText("إخفاء طبقة الترجمة");
        hideBtn.setTextColor(Color.WHITE);
        GradientDrawable closeBg = new GradientDrawable();
        closeBg.setColor(Color.parseColor("#DC2626"));
        closeBg.setCornerRadius(24f);
        hideBtn.setBackground(closeBg);
        hideBtn.setOnClickListener(v -> resultOverlayLayout.setVisibility(View.GONE));

        resultOverlayLayout.addView(headerTv);
        resultOverlayLayout.addView(scroll);
        resultOverlayLayout.addView(hideBtn);
        resultOverlayLayout.setVisibility(View.GONE);

        int overlayType = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            : WindowManager.LayoutParams.TYPE_PHONE;

        WindowManager.LayoutParams overlayParams = new WindowManager.LayoutParams(
            (int) (screenWidth * 0.90),
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        );
        overlayParams.gravity = Gravity.CENTER;

        windowManager.addView(resultOverlayLayout, overlayParams);
    }

    private void captureScreenAndTranslate() {
        floatingPillLayout.setVisibility(View.INVISIBLE);
        resultOverlayLayout.setVisibility(View.GONE);

        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            Bitmap bmp = grabScreenBitmap();
            floatingPillLayout.setVisibility(View.VISIBLE);

            if (bmp != null) {
                displayResultOverlay("⏳ جاري قراءة نصوص الشاشة وترجمتها إلى (" + currentTargetLang.toUpperCase() + ")...");
                // Scale bitmap down for fast OCR processing
                int targetW = Math.min(720, bmp.getWidth());
                int targetH = (int) ((float) bmp.getHeight() * targetW / bmp.getWidth());
                Bitmap scaled = Bitmap.createScaledBitmap(bmp, targetW, targetH, true);
                bmp.recycle();

                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                scaled.compress(Bitmap.CompressFormat.JPEG, 75, bos);
                scaled.recycle();
                String b64 = "data:image/jpeg;base64," + Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP);

                if (MainActivity.instance != null) {
                    MainActivity.instance.deliverCapturedScreenToWebView(b64);
                }
            } else {
                displayResultOverlay("⚠️ اضغط (ترجم الشاشة) مرة أخرى لالتقاط الشاشة الحالية");
            }
        }, 200);
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

    private void displayResultOverlay(String text) {
        if (resultTextView != null && resultOverlayLayout != null) {
            resultTextView.setText(text);
            resultOverlayLayout.setVisibility(View.VISIBLE);
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
        if (floatingPillLayout != null && windowManager != null) {
            windowManager.removeView(floatingPillLayout);
        }
        if (resultOverlayLayout != null && windowManager != null) {
            windowManager.removeView(resultOverlayLayout);
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
