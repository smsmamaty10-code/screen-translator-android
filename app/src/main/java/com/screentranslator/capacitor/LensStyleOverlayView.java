package com.screentranslator.capacitor;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.view.MotionEvent;
import android.view.View;
import java.util.ArrayList;
import java.util.List;

public class LensStyleOverlayView extends View {

    public static class OverlayItem {
        public final Rect rect;
        public final String translatedText;
        public final int bgColor;
        public final int textColor;

        public OverlayItem(Rect rect, String translatedText, int bgColor, int textColor) {
            this.rect = rect;
            this.translatedText = translatedText;
            this.bgColor = bgColor;
            this.textColor = textColor;
        }
    }

    private final List<OverlayItem> items = new ArrayList<>();
    private final Paint boxPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private Runnable onDismissTapListener;

    public LensStyleOverlayView(Context context) {
        super(context);
        boxPaint.setStyle(Paint.Style.FILL);
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
        for (OverlayItem item : items) {
            if (item.translatedText == null || item.translatedText.trim().isEmpty()) continue;

            RectF rf = new RectF(item.rect);
            float padX = Math.max(8f, rf.height() * 0.14f);
            float padY = Math.max(4f, rf.height() * 0.10f);
            rf.inset(-padX, -padY);

            boxPaint.setColor(item.bgColor);
            float corner = Math.min(14f, rf.height() * 0.25f);
            canvas.drawRoundRect(rf, corner, corner, boxPaint);

            int availW = Math.max(48, (int) (rf.width() - padX));
            int availH = Math.max(20, (int) (rf.height() - padY * 0.5f));

            // Auto-fit font size cleanly inside the original text's bounding box
            float fontSize = Math.min(46f, Math.max(22f, item.rect.height() * 0.76f));
            textPaint.setColor(item.textColor);
            textPaint.setFakeBoldText(true);

            StaticLayout layout = buildLayout(item.translatedText, fontSize, availW);
            while (layout.getHeight() > availH && fontSize > 18f) {
                fontSize -= 2f;
                layout = buildLayout(item.translatedText, fontSize, availW);
            }

            canvas.save();
            float dy = rf.top + Math.max(0f, (rf.height() - layout.getHeight()) / 2f);
            canvas.translate(rf.left + padX * 0.5f, dy);
            layout.draw(canvas);
            canvas.restore();
        }
    }

    private StaticLayout buildLayout(String text, float textSizePx, int widthPx) {
        textPaint.setTextSize(textSizePx);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            return StaticLayout.Builder.obtain(text, 0, text.length(), textPaint, widthPx)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setIncludePad(false)
                .build();
        } else {
            return new StaticLayout(text, textPaint, widthPx, Layout.Alignment.ALIGN_CENTER, 1.0f, 0.0f, false);
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
