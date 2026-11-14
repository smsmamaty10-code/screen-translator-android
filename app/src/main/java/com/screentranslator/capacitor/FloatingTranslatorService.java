package com.screentranslator.capacitor;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
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
import android.widget.LinearLayout;
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

    public static final String ACTION_STOP_ENTIRE_APP = "com.screentranslator.capacitor.STOP_ENTIRE_APP";

    private static FloatingTranslatorService activeInstance;
    private static String currentSourceLang = "auto";
    private static String currentTargetLang = "ar";

    private WindowManager windowManager;
    private LinearLayout miniBubbleContainer;
    private WindowManager.LayoutParams bubbleLayoutParams;
    private TextView bubbleIconTv;
    private ProgressBar bubbleSpinner;

    private LinearLayout quickMenuContainer;
    private WindowManager.LayoutParams quickMenuLayoutParams;
    private TextView sizeValueTv;
    private TextView quickColorBtn;
    private TextView quickShapeBtn;
    private TextView quickFontBtn;
    private boolean quickMenuVisible = false;

    private FrameLayout fullScreenOverlayContainer;
    private LensStyleOverlayView lensOverlayView;

    private TranslationStyleConfig styleConfig;
    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private Bitmap cachedFrameBitmap;
    private TextRecognizer textRecognizer;
    private final ExecutorService executor = Executors.newFixedThreadPool(3);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private int screenWidth = 1080;
    private int screenHeight = 2340;
    private int screenDensity = 420;
    private boolean overlayShowing = false;
    private boolean isShuttingDown = false;

    public static void updateLanguages(String src, String tgt) {
        if (src != null) currentSourceLang = src;
        if (tgt != null) currentTargetLang = tgt;
    }

    public static void reloadStyleFromPrefs(final Context ctx) {
        if (activeInstance != null) {
            activeInstance.mainHandler.post(() -> activeInstance.applyReloadedStyle(ctx));
        }
    }

    public static void showTranslatedPopupOnScreen(String text) {
        // Handled in-place by LensStyleOverlayView
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
        styleConfig = TranslationStyleConfig.load(this);
        windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);

        DisplayMetrics dm = new DisplayMetrics();
        windowManager.getDefaultDisplay().getRealMetrics(dm);
        screenWidth = dm.widthPixels;
        screenHeight = dm.heightPixels;
        screenDensity = dm.densityDpi;
    }

    private boolean viewsInitialized = false;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_STOP_ENTIRE_APP.equals(intent.getAction())) {
            shutdownEntireApplication();
            return START_NOT_STICKY;
        }
        int resultCode = intent.getIntExtra("resultCode", Activity.RESULT_CANCELED);
        Intent resultData = intent.getParcelableExtra("resultData");
        String src = intent.getStringExtra("sourceLang");
        String tgt = intent.getStringExtra("targetLang");
        if (src != null) currentSourceLang = src;
        if (tgt != null) currentTargetLang = tgt;

        // Android 14/15 requires startForeground(MEDIA_PROJECTION) ONLY after resultData is verified!
        startForegroundWithNotification();

        if (!viewsInitialized) {
            viewsInitialized = true;
            createFullScreenLensOverlay();
            createFloatingQuickMenu();
            createCompactFloatingBubble();
        }

        applyReloadedStyle(this);

        if (resultCode == Activity.RESULT_OK && resultData != null && mediaProjection == null) {
            setupMediaProjection(resultCode, resultData);
        }
        return START_NOT_STICKY;
    }

    private void applyReloadedStyle(Context ctx) {
        styleConfig = TranslationStyleConfig.load(ctx != null ? ctx : this);
        if (lensOverlayView != null) {
            lensOverlayView.setStyleConfig(styleConfig);
        }
        refreshQuickMenuLabels();
    }

    private void startForegroundWithNotification() {
        String channelId = "lens_screen_translator_v5_channel";
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel chan = new NotificationChannel(
                channelId,
                "عدسة مترجم الشاشة الفوري v5",
                NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.createNotificationChannel(chan);
            builder = new Notification.Builder(this, channelId);
        } else {
            builder = new Notification.Builder(this);
        }

        Intent stopIntent = new Intent(this, FloatingTranslatorService.class);
        stopIntent.setAction(ACTION_STOP_ENTIRE_APP);
        int piFlags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
            ? (PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE)
            : PendingIntent.FLAG_UPDATE_CURRENT;
        PendingIntent stopPi = PendingIntent.getService(this, 99, stopIntent, piFlags);

        Notification.Action stopAction = new Notification.Action.Builder(
            android.R.drawable.ic_menu_close_clear_cancel,
            "إيقاف التطبيق بالكامل",
            stopPi
        ).build();

        Notification notification = builder
            .setContentTitle("عدسة الترجمة الفورية v5 نشطة")
            .setContentText("اضغط (تر) للترجمة أو (✕⚙) أسفل الزر العائم للتخصيص وإيقاف التطبيق")
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .addAction(stopAction)
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
            "LensScreenTranslatorV5",
            screenWidth,
            screenHeight,
            screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader.getSurface(),
            null,
            null
        );
    }

    private GradientDrawable makeRoundedBg(String fillHex, String strokeHex, int radiusDp, int strokeDp) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(Color.parseColor(fillHex));
        gd.setCornerRadius(dp(radiusDp));
        if (strokeHex != null && strokeDp > 0) {
            gd.setStroke(dp(strokeDp), Color.parseColor(strokeHex));
        }
        return gd;
    }

    /**
     * طبقة عدسة جوجل بملء الشاشة مع شريط تحكم سفلي يضم إخفاء الترجمة + المظهر + إيقاف التطبيق بالكامل
     */
    private void createFullScreenLensOverlay() {
        fullScreenOverlayContainer = new FrameLayout(this);
        lensOverlayView = new LensStyleOverlayView(this);
        lensOverlayView.setStyleConfig(styleConfig);
        lensOverlayView.setOnDismissTapListener(this::hideLensOverlay);

        fullScreenOverlayContainer.addView(lensOverlayView, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ));

        LinearLayout bottomBar = new LinearLayout(this);
        bottomBar.setOrientation(LinearLayout.HORIZONTAL);
        bottomBar.setGravity(Gravity.CENTER);
        bottomBar.setPadding(dp(10), dp(6), dp(10), dp(6));
        bottomBar.setBackground(makeRoundedBg("#E60F172A", "#38BDF8", 22, 1));

        TextView hideBtn = new TextView(this);
        hideBtn.setText("✕ إخفاء الترجمة");
        hideBtn.setTextColor(Color.WHITE);
        hideBtn.setTextSize(11.5f);
        hideBtn.getPaint().setFakeBoldText(true);
        hideBtn.setPadding(dp(10), dp(5), dp(10), dp(5));
        hideBtn.setOnClickListener(v -> hideLensOverlay());

        TextView styleBtn = new TextView(this);
        styleBtn.setText("🎨 المظهر");
        styleBtn.setTextColor(Color.parseColor("#7DD3FC"));
        styleBtn.setTextSize(11.5f);
        styleBtn.getPaint().setFakeBoldText(true);
        styleBtn.setPadding(dp(10), dp(5), dp(10), dp(5));
        styleBtn.setOnClickListener(v -> toggleQuickMenu());

        TextView quitBtn = new TextView(this);
        quitBtn.setText("🔴 إيقاف التطبيق");
        quitBtn.setTextColor(Color.parseColor("#FCA5A5"));
        quitBtn.setTextSize(11.5f);
        quitBtn.getPaint().setFakeBoldText(true);
        quitBtn.setPadding(dp(10), dp(5), dp(10), dp(5));
        quitBtn.setOnClickListener(v -> shutdownEntireApplication());

        bottomBar.addView(hideBtn);
        bottomBar.addView(styleBtn);
        bottomBar.addView(quitBtn);

        FrameLayout.LayoutParams barLp = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        );
        barLp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        barLp.bottomMargin = dp(52);
        fullScreenOverlayContainer.addView(bottomBar, barLp);

        int overlayType = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            : WindowManager.LayoutParams.TYPE_PHONE;

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        );
        lp.gravity = Gravity.TOP | Gravity.START;

        fullScreenOverlayContainer.setVisibility(View.GONE);
        windowManager.addView(fullScreenOverlayContainer, lp);
    }

    /**
     * قائمة التحكم السريع والإيقاف المباشر المنبثقة من الزر العائم نفسه
     */
    private void createFloatingQuickMenu() {
        quickMenuContainer = new LinearLayout(this);
        quickMenuContainer.setOrientation(LinearLayout.VERTICAL);
        quickMenuContainer.setPadding(dp(12), dp(10), dp(12), dp(10));
        quickMenuContainer.setBackground(makeRoundedBg("#F50F172A", "#38BDF8", 16, 1));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            quickMenuContainer.setElevation(dp(12));
        }

        // Header row
        LinearLayout headerRow = new LinearLayout(this);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView titleTv = new TextView(this);
        titleTv.setText("⚡ تحكم الزر العائم");
        titleTv.setTextColor(Color.parseColor("#7DD3FC"));
        titleTv.setTextSize(12.5f);
        titleTv.getPaint().setFakeBoldText(true);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        headerRow.addView(titleTv, titleLp);

        TextView closeMenuTv = new TextView(this);
        closeMenuTv.setText("✕");
        closeMenuTv.setTextColor(Color.parseColor("#CBD5E1"));
        closeMenuTv.setTextSize(13f);
        closeMenuTv.getPaint().setFakeBoldText(true);
        closeMenuTv.setPadding(dp(8), dp(2), dp(4), dp(2));
        closeMenuTv.setOnClickListener(v -> hideQuickMenu());
        headerRow.addView(closeMenuTv);
        quickMenuContainer.addView(headerRow);

        // Instant Translate Button
        TextView translateNowBtn = makeMenuButton("⚡ ترجم الشاشة الآن", "#0284C7", "#FFFFFF");
        translateNowBtn.setOnClickListener(v -> {
            hideQuickMenu();
            triggerInPlaceLensTranslation();
        });
        quickMenuContainer.addView(translateNowBtn, menuItemMargin());

        // Font Size Row (A- / 100% / A+)
        LinearLayout sizeRow = new LinearLayout(this);
        sizeRow.setOrientation(LinearLayout.HORIZONTAL);
        sizeRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView minusBtn = makeMenuButton("➖ تصغير", "#1E293B", "#E2E8F0");
        minusBtn.setOnClickListener(v -> {
            styleConfig.adjustFontScale(-15);
            styleConfig.save(this);
            if (lensOverlayView != null) lensOverlayView.setStyleConfig(styleConfig);
            refreshQuickMenuLabels();
            syncWebViewIfAlive();
        });

        sizeValueTv = new TextView(this);
        sizeValueTv.setTextColor(Color.parseColor("#6EE7B7"));
        sizeValueTv.setTextSize(12f);
        sizeValueTv.getPaint().setFakeBoldText(true);
        sizeValueTv.setGravity(Gravity.CENTER);
        sizeValueTv.setPadding(dp(8), dp(4), dp(8), dp(4));

        TextView plusBtn = makeMenuButton("➕ تكبير", "#1E293B", "#E2E8F0");
        plusBtn.setOnClickListener(v -> {
            styleConfig.adjustFontScale(15);
            styleConfig.save(this);
            if (lensOverlayView != null) lensOverlayView.setStyleConfig(styleConfig);
            refreshQuickMenuLabels();
            syncWebViewIfAlive();
        });

        sizeRow.addView(minusBtn, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        sizeRow.addView(sizeValueTv, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        sizeRow.addView(plusBtn, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        quickMenuContainer.addView(sizeRow, menuItemMargin());

        // Quick Color Cycle Button
        quickColorBtn = makeMenuButton("🎨 اللون: عدسة تلقائي", "#1E293B", "#F8FAFC");
        quickColorBtn.setOnClickListener(v -> {
            styleConfig.cycleColorPreset();
            styleConfig.save(this);
            if (lensOverlayView != null) lensOverlayView.setStyleConfig(styleConfig);
            refreshQuickMenuLabels();
            syncWebViewIfAlive();
        });
        quickMenuContainer.addView(quickColorBtn, menuItemMargin());

        // Quick Shape Cycle Button
        quickShapeBtn = makeMenuButton("▭ الشكل: عدسة ناعمة", "#1E293B", "#F8FAFC");
        quickShapeBtn.setOnClickListener(v -> {
            styleConfig.cycleBoxShape();
            styleConfig.save(this);
            if (lensOverlayView != null) lensOverlayView.setStyleConfig(styleConfig);
            refreshQuickMenuLabels();
            syncWebViewIfAlive();
        });
        quickMenuContainer.addView(quickShapeBtn, menuItemMargin());

        // Quick Font Cycle Button
        quickFontBtn = makeMenuButton("أب الخط: كوفي عصري", "#1E293B", "#F8FAFC");
        quickFontBtn.setOnClickListener(v -> {
            styleConfig.cycleFontFamily();
            styleConfig.save(this);
            if (lensOverlayView != null) lensOverlayView.setStyleConfig(styleConfig);
            refreshQuickMenuLabels();
            syncWebViewIfAlive();
        });
        quickMenuContainer.addView(quickFontBtn, menuItemMargin());

        // Open Full App Settings Button
        TextView openSettingsBtn = makeMenuButton("⚙️ فتح نافذة التخصيص الكاملة", "#0F172A", "#7DD3FC");
        openSettingsBtn.setBackground(makeRoundedBg("#172554", "#38BDF8", 10, 1));
        openSettingsBtn.setOnClickListener(v -> {
            hideQuickMenu();
            Intent openApp = new Intent(this, MainActivity.class);
            openApp.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(openApp);
        });
        quickMenuContainer.addView(openSettingsBtn, menuItemMargin());

        // Stop Floating Bubble Only
        TextView stopBubbleOnlyBtn = makeMenuButton("⏹ إخفاء الزر العائم فقط", "#334155", "#E2E8F0");
        stopBubbleOnlyBtn.setOnClickListener(v -> {
            Toast.makeText(this, "تم إخفاء الزر العائم", Toast.LENGTH_SHORT).show();
            stopSelf();
        });
        quickMenuContainer.addView(stopBubbleOnlyBtn, menuItemMargin());

        // STOP ENTIRE APPLICATION BUTTON (Requested explicitly by user!)
        TextView stopEntireAppBtn = makeMenuButton("🔴 إيقاف التطبيق بالكامل", "#DC2626", "#FFFFFF");
        stopEntireAppBtn.setOnClickListener(v -> shutdownEntireApplication());
        quickMenuContainer.addView(stopEntireAppBtn, menuItemMargin());

        refreshQuickMenuLabels();

        int overlayType = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            : WindowManager.LayoutParams.TYPE_PHONE;

        quickMenuLayoutParams = new WindowManager.LayoutParams(
            dp(232),
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        );
        quickMenuLayoutParams.gravity = Gravity.TOP | Gravity.START;
        quickMenuLayoutParams.x = Math.max(dp(12), screenWidth - dp(284));
        quickMenuLayoutParams.y = screenHeight / 4;

        quickMenuContainer.setVisibility(View.GONE);
        windowManager.addView(quickMenuContainer, quickMenuLayoutParams);
    }

    private LinearLayout.LayoutParams menuItemMargin() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
        lp.topMargin = dp(6);
        return lp;
    }

    private TextView makeMenuButton(String label, String bgHex, String textHex) {
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextColor(Color.parseColor(textHex));
        tv.setTextSize(11.5f);
        tv.getPaint().setFakeBoldText(true);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(8), dp(7), dp(8), dp(7));
        tv.setBackground(makeRoundedBg(bgHex, "#475569", 10, 1));
        return tv;
    }

    private void refreshQuickMenuLabels() {
        if (styleConfig == null) return;
        if (sizeValueTv != null) sizeValueTv.setText(styleConfig.fontScalePercent + "%");
        if (quickColorBtn != null) quickColorBtn.setText("🎨 اللون: " + styleConfig.getColorLabelAr());
        if (quickShapeBtn != null) quickShapeBtn.setText("▭ الشكل: " + styleConfig.getShapeLabelAr());
        if (quickFontBtn != null) quickFontBtn.setText("أب الخط: " + styleConfig.getFontLabelAr());
    }

    private void syncWebViewIfAlive() {
        if (MainActivity.instance != null && styleConfig != null) {
            MainActivity.instance.notifyStyleChangedFromService(styleConfig.toJsonString());
        }
    }

    private void toggleQuickMenu() {
        if (quickMenuVisible) {
            hideQuickMenu();
        } else {
            showQuickMenuNearBubble();
        }
    }

    private void showQuickMenuNearBubble() {
        if (quickMenuContainer == null || bubbleLayoutParams == null) return;
        refreshQuickMenuLabels();
        int menuW = dp(232);
        int bubbleW = dp(40);
        if (bubbleLayoutParams.x > screenWidth / 2) {
            quickMenuLayoutParams.x = Math.max(dp(8), bubbleLayoutParams.x - menuW - dp(8));
        } else {
            quickMenuLayoutParams.x = Math.min(screenWidth - menuW - dp(8), bubbleLayoutParams.x + bubbleW + dp(8));
        }
        quickMenuLayoutParams.y = Math.max(dp(36), Math.min(screenHeight - dp(360), bubbleLayoutParams.y - dp(20)));
        windowManager.updateViewLayout(quickMenuContainer, quickMenuLayoutParams);
        quickMenuContainer.setVisibility(View.VISIBLE);
        quickMenuVisible = true;
    }

    private void hideQuickMenu() {
        quickMenuVisible = false;
        if (quickMenuContainer != null) {
            quickMenuContainer.setVisibility(View.GONE);
        }
    }

    /**
     * كبسولة الزر العائم المدمجة بحجم صغير جداً (38dp عرض فقط):
     * 1) الدائرة العلوية (تر): ضغطة واحدة لترجمة الشاشة فوراً (أو ضغطة مطولة لإيقاف التطبيق بالكامل)
     * 2) الزر السفلي المدمج (✕⚙): ضغطة واحدة تفتح قائمة التحكم السريع وإيقاف التطبيق بالكامل!
     */
    private void createCompactFloatingBubble() {
        final int bubbleW = dp(44);
        miniBubbleContainer = new LinearLayout(this);
        miniBubbleContainer.setOrientation(LinearLayout.VERTICAL);
        miniBubbleContainer.setGravity(Gravity.CENTER_HORIZONTAL);

        // Part 1: Main 36dp Circular Translate Lens Button
        FrameLayout mainCircle = new FrameLayout(this);
        GradientDrawable circleBg = new GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            new int[]{Color.parseColor("#F00284C7"), Color.parseColor("#F04F46E5")}
        );
        circleBg.setShape(GradientDrawable.OVAL);
        circleBg.setStroke(dp(1), Color.parseColor("#7DD3FC"));
        mainCircle.setBackground(circleBg);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            mainCircle.setElevation(dp(8));
        }

        bubbleIconTv = new TextView(this);
        bubbleIconTv.setText("تر");
        bubbleIconTv.setTextColor(Color.WHITE);
        bubbleIconTv.setTextSize(12f);
        bubbleIconTv.getPaint().setFakeBoldText(true);
        bubbleIconTv.setGravity(Gravity.CENTER);

        bubbleSpinner = new ProgressBar(this);
        bubbleSpinner.setVisibility(View.GONE);
        int pad = dp(7);
        bubbleSpinner.setPadding(pad, pad, pad, pad);

        mainCircle.addView(bubbleIconTv, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ));
        mainCircle.addView(bubbleSpinner, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ));

        LinearLayout.LayoutParams circleLp = new LinearLayout.LayoutParams(dp(42), dp(42));
        miniBubbleContainer.addView(mainCircle, circleLp);

        // Part 2: Attached Mini Stop & Quick-Settings Pill right below (36dp x 22dp)
        TextView stopAndMenuPill = new TextView(this);
        stopAndMenuPill.setText("✕⚙");
        stopAndMenuPill.setTextColor(Color.parseColor("#FCA5A5"));
        stopAndMenuPill.setTextSize(9.5f);
        stopAndMenuPill.getPaint().setFakeBoldText(true);
        stopAndMenuPill.setGravity(Gravity.CENTER);
        stopAndMenuPill.setBackground(makeRoundedBg("#EB0F172A", "#EF4444", 10, 1));

        LinearLayout.LayoutParams pillLp = new LinearLayout.LayoutParams(dp(42), dp(25));
        pillLp.topMargin = dp(3);
        miniBubbleContainer.addView(stopAndMenuPill, pillLp);

        int overlayType = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            : WindowManager.LayoutParams.TYPE_PHONE;

        bubbleLayoutParams = new WindowManager.LayoutParams(
            bubbleW,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        );
        bubbleLayoutParams.gravity = Gravity.TOP | Gravity.START;
        bubbleLayoutParams.x = screenWidth - bubbleW - dp(8);
        bubbleLayoutParams.y = screenHeight / 3;

        // Shared drag handler helper for both parts, with distinct tap actions!
        mainCircle.setOnTouchListener(createBubbleTouchListener(bubbleW, true));
        stopAndMenuPill.setOnTouchListener(createBubbleTouchListener(bubbleW, false));

        miniBubbleContainer.setAlpha(0.90f);
        windowManager.addView(miniBubbleContainer, bubbleLayoutParams);
    }

    private View.OnTouchListener createBubbleTouchListener(final int bubbleW, final boolean isTranslatePart) {
        return new View.OnTouchListener() {
            private int initX, initY;
            private float downRawX, downRawY;
            private long downTime;
            private boolean moved;
            private boolean longPressTriggered;
            private final Runnable longPressRunnable = () -> {
                if (!moved && !longPressTriggered) {
                    longPressTriggered = true;
                    vibrateLight();
                    showQuickMenuNearBubble();
                }
            };

            private void vibrateLight() {
                if (miniBubbleContainer != null) {
                    miniBubbleContainer.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
                }
            }

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        initX = bubbleLayoutParams.x;
                        initY = bubbleLayoutParams.y;
                        downRawX = event.getRawX();
                        downRawY = event.getRawY();
                        downTime = System.currentTimeMillis();
                        moved = false;
                        longPressTriggered = false;
                        miniBubbleContainer.setAlpha(1.0f);
                        v.setScaleX(0.92f);
                        v.setScaleY(0.92f);
                        mainHandler.postDelayed(longPressRunnable, 850);
                        return true;

                    case MotionEvent.ACTION_MOVE:
                        float dx = event.getRawX() - downRawX;
                        float dy = event.getRawY() - downRawY;
                        if (Math.hypot(dx, dy) > dp(16)) {
                            moved = true;
                            v.setScaleX(1.0f);
                            v.setScaleY(1.0f);
                            mainHandler.removeCallbacks(longPressRunnable);
                            hideQuickMenu();
                            bubbleLayoutParams.x = Math.max(0, Math.min(screenWidth - bubbleW, initX + (int) dx));
                            bubbleLayoutParams.y = Math.max(dp(24), Math.min(screenHeight - dp(85), initY + (int) dy));
                            windowManager.updateViewLayout(miniBubbleContainer, bubbleLayoutParams);
                        }
                        return true;

                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        v.setScaleX(1.0f);
                        v.setScaleY(1.0f);
                        mainHandler.removeCallbacks(longPressRunnable);
                        if (longPressTriggered) return true;
                        if (event.getActionMasked() == MotionEvent.ACTION_UP && !moved) {
                            if (isTranslatePart) {
                                hideQuickMenu();
                                if (overlayShowing) {
                                    hideLensOverlay();
                                } else {
                                    triggerInPlaceLensTranslation();
                                }
                            } else {
                                toggleQuickMenu();
                            }
                        } else if (moved) {
                            bubbleLayoutParams.x = (bubbleLayoutParams.x + bubbleW / 2 < screenWidth / 2)
                                ? dp(6)
                                : (screenWidth - bubbleW - dp(6));
                            windowManager.updateViewLayout(miniBubbleContainer, bubbleLayoutParams);
                            miniBubbleContainer.setAlpha(0.90f);
                        }
                        return true;
                }
                return false;
            }
        };
    }

    /**
     * إيقاف التطبيق بالكامل (يغلق الخدمة العائمة + الإشعار + نافذة التطبيق الرئيسية نهائياً)
     */
    public void shutdownEntireApplication() {
        if (isShuttingDown) return;
        isShuttingDown = true;
        Toast.makeText(this, "تم إيقاف تطبيق مترجم الشاشة بالكامل", Toast.LENGTH_SHORT).show();
        hideQuickMenu();
        hideLensOverlay();
        MainActivity.closeEntireApp();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(Service.STOP_FOREGROUND_REMOVE);
        } else {
            stopForeground(true);
        }
        stopSelf();
    }

    private void hideLensOverlay() {
        overlayShowing = false;
        if (lensOverlayView != null) lensOverlayView.clearItems();
        if (fullScreenOverlayContainer != null) fullScreenOverlayContainer.setVisibility(View.GONE);
        if (miniBubbleContainer != null && bubbleIconTv != null) {
            bubbleIconTv.setText("تر");
            miniBubbleContainer.setAlpha(0.90f);
        }
    }

    private void triggerInPlaceLensTranslation() {
        if (miniBubbleContainer == null) return;
        hideQuickMenu();
        hideLensOverlay();

        // Hide floating UI for 150ms so screenshot is 100% clean
        miniBubbleContainer.setVisibility(View.INVISIBLE);

        mainHandler.postDelayed(() -> {
            Bitmap firstTry = grabScreenBitmap();
            if (firstTry != null) {
                proceedWithCapturedBitmap(firstTry);
            } else {
                mainHandler.postDelayed(() -> {
                    Bitmap secondTry = grabScreenBitmap();
                    if (secondTry != null) {
                        proceedWithCapturedBitmap(secondTry);
                    } else {
                        miniBubbleContainer.setVisibility(View.VISIBLE);
                        Toast.makeText(this, "جاري تحديث الشاشة، اضغط مرة أخرى", Toast.LENGTH_SHORT).show();
                    }
                }, 90);
            }
        }, 150);
    }

    private void proceedWithCapturedBitmap(final Bitmap screenBmp) {
        if (miniBubbleContainer != null) {
            miniBubbleContainer.setVisibility(View.VISIBLE);
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
                Toast.makeText(this, "تعذر قراءة نصوص الشاشة", Toast.LENGTH_SHORT).show();
            });
    }

    private void processDetectedLinesInPlace(Bitmap screenBmp, Text visionText) {
        List<Rect> lineRects = new ArrayList<>();
        List<String> lineStrings = new ArrayList<>();
        List<Integer> sampledBgList = new ArrayList<>();

        for (Text.TextBlock block : visionText.getTextBlocks()) {
            for (Text.Line line : block.getLines()) {
                String raw = line.getText() != null ? line.getText().trim() : "";
                Rect box = line.getBoundingBox();
                if (raw.length() < 2 || box == null) continue;

                int sampledBg = sampleBackgroundColorAroundRect(screenBmp, box);
                lineRects.add(box);
                lineStrings.add(raw);
                sampledBgList.add(sampledBg);
            }
        }
        screenBmp.recycle();

        if (lineStrings.isEmpty()) {
            mainHandler.post(() -> {
                resetBubbleState();
                Toast.makeText(this, "لا توجد نصوص واضحة للترجمة على هذه الشاشة", Toast.LENGTH_SHORT).show();
            });
            return;
        }

        List<String> translatedLines = batchTranslateLines(lineStrings, currentSourceLang, currentTargetLang);

        final List<LensStyleOverlayView.OverlayItem> overlayItems = new ArrayList<>();
        for (int i = 0; i < lineRects.size(); i++) {
            String tr = (i < translatedLines.size()) ? translatedLines.get(i) : lineStrings.get(i);
            overlayItems.add(new LensStyleOverlayView.OverlayItem(
                lineRects.get(i),
                tr,
                sampledBgList.get(i)
            ));
        }

        mainHandler.post(() -> {
            resetBubbleState();
            bubbleIconTv.setText("✕");
            styleConfig = TranslationStyleConfig.load(this);
            lensOverlayView.setStyleConfig(styleConfig);
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

            if (red < 35 && green < 35 && blue < 40) {
                return Color.rgb(54, 58, 66);
            }
            return Color.rgb(red, green, blue);
        } catch (Exception e) {
            return Color.rgb(54, 58, 66);
        }
    }

    private List<String> batchTranslateLines(List<String> lines, String sl, String tl) {
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

                String[] split = fullTranslated.toString().split("\\r?\\n");
                List<String> result = new ArrayList<>();
                for (int i = 0; i < lines.size(); i++) {
                    result.add(i < split.length ? split[i].trim() : lines.get(i));
                }
                return result;
            }
        } catch (Exception ignored) {
        }

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
        if (image != null) {
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

                if (cachedFrameBitmap != null && !cachedFrameBitmap.isRecycled()) {
                    cachedFrameBitmap.recycle();
                }
                cachedFrameBitmap = cropped.copy(Bitmap.Config.ARGB_8888, false);
                return cropped;
            } catch (Exception e) {
                return null;
            } finally {
                image.close();
            }
        }
        if (cachedFrameBitmap != null && !cachedFrameBitmap.isRecycled()) {
            return cachedFrameBitmap.copy(Bitmap.Config.ARGB_8888, false);
        }
        return null;
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
        if (cachedFrameBitmap != null && !cachedFrameBitmap.isRecycled()) {
            cachedFrameBitmap.recycle();
            cachedFrameBitmap = null;
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
        try {
            if (quickMenuContainer != null && windowManager != null) {
                windowManager.removeView(quickMenuContainer);
            }
        } catch (Exception ignored) {}
        try {
            if (miniBubbleContainer != null && windowManager != null) {
                windowManager.removeView(miniBubbleContainer);
            }
        } catch (Exception ignored) {}
        try {
            if (fullScreenOverlayContainer != null && windowManager != null) {
                windowManager.removeView(fullScreenOverlayContainer);
            }
        } catch (Exception ignored) {}
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
