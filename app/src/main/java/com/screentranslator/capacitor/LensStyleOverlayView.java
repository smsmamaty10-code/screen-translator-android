package com.screentranslator.capacitor;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Build;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;
import java.util.ArrayList;
import java.util.List;

public class LensStyleOverlayView extends View {

    public static class OverlayItem {
        public final Rect rect;
        public final String translatedText;
        public final int sampledBgRgb;

        public OverlayItem(Rect rect, String translatedText, int sampledBgRgb) {
            this.rect = rect;
            this.translatedText = translatedText;
            this.sampledBgRgb = sampledBgRgb;
        }
    }

    private final List<OverlayItem> items = new ArrayList<>();
    private final Paint boxPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint accentPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint outlineTextPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);

    private TranslationStyleConfig styleConfig;
    private Runnable onDismissTapListener;

    public LensStyleOverlayView(Context context) {
        super(context);
        styleConfig = TranslationStyleConfig.load(context);
        boxPaint.setStyle(Paint.Style.FILL);
        borderPaint.setStyle(Paint.Style.STROKE);
        accentPaint.setStyle(Paint.Style.FILL);
        outlineTextPaint.setStyle(Paint.Style.STROKE);
        outlineTextPaint.setStrokeJoin(Paint.Join.ROUND);
        outlineTextPaint.setStrokeCap(Paint.Cap.ROUND);
    }

    private float dp(float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    public void setStyleConfig(TranslationStyleConfig cfg) {
        if (cfg != null) {
            this.styleConfig = cfg;
            invalidate();
        }
    }

    public TranslationStyleConfig getStyleConfig() {
        return styleConfig;
    }

    public void setOnDismissTapListener(Runnable listener) {
        this.onDismissTapListener = listener;
    }

    public void setItems(List<OverlayItem> newItems) {
        items.clear();
        if (newItems != null) {
            items.addAll(newItems);
        }
        invalidate();
    }

    public void clearItems() {
        items.clear();
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (styleConfig == null) {
            styleConfig = TranslationStyleConfig.load(getContext());
        }

        Typeface tf = styleConfig.resolveTypeface(getContext());
        textPaint.setTypeface(tf);
        textPaint.setFakeBoldText(styleConfig.isBold);
        outlineTextPaint.setTypeface(tf);
        outlineTextPaint.setFakeBoldText(styleConfig.isBold);

        float scale = styleConfig.fontScalePercent / 100f;
        int canvasW = Math.max(1, getWidth());
        int canvasH = Math.max(1, getHeight());

        for (OverlayItem item : items) {
            if (item.translatedText == null || item.translatedText.trim().isEmpty()) continue;

            RectF rf = new RectF(item.rect);
            float padX = Math.max(dp(6f), rf.height() * 0.15f * Math.max(1f, scale * 0.85f));
            float padY = Math.max(dp(4f), rf.height() * 0.12f * Math.max(1f, scale * 0.85f));
            rf.inset(-padX, -padY);

            // If user increased font scale > 100%, allow wider horizontal room if box was narrow
            if (scale > 1.05f) {
                float extraW = Math.min(dp(80f), rf.width() * (scale - 1.0f) * 0.45f);
                rf.inset(-extraW * 0.5f, 0f);
            }
            rf.left = Math.max(dp(4f), rf.left);
            rf.right = Math.min(canvasW - dp(4f), rf.right);

            int availW = Math.max((int) dp(54f), (int) (rf.width() - padX * 1.2f));
            float baseFontPx = Math.min(dp(24f), Math.max(dp(11.5f), item.rect.height() * 0.72f));
            float fontSize = Math.max(dp(10f), Math.min(dp(42f), baseFontPx * scale));

            StaticLayout layout = buildLayout(item.translatedText, fontSize, availW, textPaint);

            // Only shrink text to original box height when scale <= 1.05; when user enlarges text, expand box height!
            if (scale <= 1.05f) {
                int targetAvailH = Math.max((int) dp(18f), (int) (rf.height() - padY * 0.6f));
                float minAllowed = Math.max(dp(9.5f), dp(11f) * scale);
                while (layout.getHeight() > targetAvailH && fontSize > minAllowed) {
                    fontSize -= dp(1f);
                    layout = buildLayout(item.translatedText, fontSize, availW, textPaint);
                }
            }

            float requiredH = layout.getHeight() + padY * 1.6f;
            if (requiredH > rf.height()) {
                float diff = (requiredH - rf.height()) * 0.5f;
                rf.top = Math.max(dp(4f), rf.top - diff);
                rf.bottom = Math.min(canvasH - dp(4f), rf.bottom + diff);
            }

            int bgColor = styleConfig.resolveBgColor(item.sampledBgRgb);
            int txtColor = styleConfig.resolveTextColor(item.sampledBgRgb);
            int brdColor = styleConfig.resolveBorderColor(item.sampledBgRgb);

            String shape = styleConfig.boxShape != null ? styleConfig.boxShape : "lens_auto";

            if (!"outline_only".equals(shape)) {
                boxPaint.setColor(bgColor);
                borderPaint.setColor(brdColor);

                if ("capsule_pill".equals(shape)) {
                    float r = rf.height() * 0.5f;
                    canvas.drawRoundRect(rf, r, r, boxPaint);
                    borderPaint.setStrokeWidth(dp(1.6f));
                    canvas.drawRoundRect(rf, r, r, borderPaint);
                } else if ("rounded_card".equals(shape)) {
                    float r = dp(13f);
                    canvas.drawRoundRect(rf, r, r, boxPaint);
                    borderPaint.setStrokeWidth(dp(2.0f));
                    canvas.drawRoundRect(rf, r, r, borderPaint);
                } else if ("sharp_banner".equals(shape)) {
                    float r = dp(3f);
                    canvas.drawRoundRect(rf, r, r, boxPaint);
                    borderPaint.setStrokeWidth(dp(1.2f));
                    canvas.drawRoundRect(rf, r, r, borderPaint);
                    accentPaint.setColor(brdColor);
                    RectF sideStripe = new RectF(rf.right - dp(4.5f), rf.top, rf.right, rf.bottom);
                    canvas.drawRoundRect(sideStripe, r, r, accentPaint);
                } else {
                    // lens_auto
                    float r = Math.min(dp(9f), rf.height() * 0.24f);
                    canvas.drawRoundRect(rf, r, r, boxPaint);
                    if (!"lens_smart".equals(styleConfig.colorPreset)) {
                        borderPaint.setStrokeWidth(dp(1.2f));
                        canvas.drawRoundRect(rf, r, r, borderPaint);
                    }
                }
            }

            float dx = rf.left + (rf.width() - availW) * 0.5f;
            float dy = rf.top + Math.max(0f, (rf.height() - layout.getHeight()) * 0.5f);

            canvas.save();
            canvas.translate(dx, dy);

            if ("outline_only".equals(shape)) {
                int txtLum = (int) (0.299 * Color.red(txtColor) + 0.587 * Color.green(txtColor) + 0.114 * Color.blue(txtColor));
                int haloColor = (txtLum > 140) ? Color.parseColor("#F0090D16") : Color.parseColor("#F0FFFFFF");
                outlineTextPaint.setColor(haloColor);
                outlineTextPaint.setStrokeWidth(dp(3.5f));
                StaticLayout strokeLayout = buildLayout(item.translatedText, fontSize, availW, outlineTextPaint);
                strokeLayout.draw(canvas);
            }

            textPaint.setColor(txtColor);
            // Rebuild final layout with updated textPaint color
            StaticLayout fillLayout = buildLayout(item.translatedText, fontSize, availW, textPaint);
            fillLayout.draw(canvas);
            canvas.restore();
        }
    }

    private StaticLayout buildLayout(String text, float textSizePx, int widthPx, TextPaint paint) {
        paint.setTextSize(textSizePx);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return StaticLayout.Builder.obtain(text, 0, text.length(), paint, widthPx)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setIncludePad(false)
                .build();
        } else {
            return new StaticLayout(text, paint, widthPx, Layout.Alignment.ALIGN_CENTER, 1.0f, 0.0f, false);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            if (onDismissTapListener != null) {
                onDismissTapListener.run();
            }
            return true;
        }
        return super.onTouchEvent(event);
    }
}
