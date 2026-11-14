package com.screentranslator.capacitor;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import org.json.JSONObject;
import java.util.HashMap;
import java.util.Map;

public class TranslationStyleConfig {

    private static final String PREFS_NAME = "translator_style_v5";
    private static final Map<String, Typeface> TYPEFACE_CACHE = new HashMap<>();

    // lens_auto | rounded_card | capsule_pill | sharp_banner | outline_only
    public String boxShape = "lens_auto";

    // lens_smart | cyber_dark | cinema_gold | paper_light | royal_purple | custom
    public String colorPreset = "lens_smart";

    public String customTextColor = "#6EE7B7";
    public String customBgColor = "#0F172A";
    public String customBorderColor = "#38BDF8";
    public int bgAlphaPercent = 92;

    // kufi | sans | naskh | condensed | mono
    public String fontFamily = "kufi";
    public boolean isBold = true;

    // 70 .. 180 (%)
    public int fontScalePercent = 100;

    public static TranslationStyleConfig load(Context ctx) {
        TranslationStyleConfig cfg = new TranslationStyleConfig();
        if (ctx == null) return cfg;
        SharedPreferences sp = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        cfg.boxShape = sp.getString("boxShape", "lens_auto");
        cfg.colorPreset = sp.getString("colorPreset", "lens_smart");
        cfg.customTextColor = sp.getString("customTextColor", "#6EE7B7");
        cfg.customBgColor = sp.getString("customBgColor", "#0F172A");
        cfg.customBorderColor = sp.getString("customBorderColor", "#38BDF8");
        cfg.bgAlphaPercent = clamp(sp.getInt("bgAlphaPercent", 92), 20, 100);
        cfg.fontFamily = sp.getString("fontFamily", "kufi");
        cfg.isBold = sp.getBoolean("isBold", true);
        cfg.fontScalePercent = clamp(sp.getInt("fontScalePercent", 100), 70, 180);
        return cfg;
    }

    public void save(Context ctx) {
        if (ctx == null) return;
        SharedPreferences sp = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        sp.edit()
            .putString("boxShape", boxShape)
            .putString("colorPreset", colorPreset)
            .putString("customTextColor", customTextColor)
            .putString("customBgColor", customBgColor)
            .putString("customBorderColor", customBorderColor)
            .putInt("bgAlphaPercent", clamp(bgAlphaPercent, 20, 100))
            .putString("fontFamily", fontFamily)
            .putBoolean("isBold", isBold)
            .putInt("fontScalePercent", clamp(fontScalePercent, 70, 180))
            .apply();
    }

    public static TranslationStyleConfig fromJsonAndSave(Context ctx, String jsonStr) {
        TranslationStyleConfig cfg = load(ctx);
        if (jsonStr == null || jsonStr.trim().isEmpty()) return cfg;
        try {
            JSONObject obj = new JSONObject(jsonStr);
            if (obj.has("boxShape")) cfg.boxShape = obj.optString("boxShape", cfg.boxShape);
            if (obj.has("colorPreset")) cfg.colorPreset = obj.optString("colorPreset", cfg.colorPreset);
            if (obj.has("customTextColor")) cfg.customTextColor = obj.optString("customTextColor", cfg.customTextColor);
            if (obj.has("customBgColor")) cfg.customBgColor = obj.optString("customBgColor", cfg.customBgColor);
            if (obj.has("customBorderColor")) cfg.customBorderColor = obj.optString("customBorderColor", cfg.customBorderColor);
            if (obj.has("bgAlphaPercent")) cfg.bgAlphaPercent = clamp(obj.optInt("bgAlphaPercent", cfg.bgAlphaPercent), 20, 100);
            if (obj.has("fontFamily")) cfg.fontFamily = obj.optString("fontFamily", cfg.fontFamily);
            if (obj.has("isBold")) cfg.isBold = obj.optBoolean("isBold", cfg.isBold);
            if (obj.has("fontScalePercent")) cfg.fontScalePercent = clamp(obj.optInt("fontScalePercent", cfg.fontScalePercent), 70, 180);
            cfg.save(ctx);
        } catch (Exception ignored) {
        }
        return cfg;
    }

    public String toJsonString() {
        try {
            JSONObject obj = new JSONObject();
            obj.put("boxShape", boxShape);
            obj.put("colorPreset", colorPreset);
            obj.put("customTextColor", customTextColor);
            obj.put("customBgColor", customBgColor);
            obj.put("customBorderColor", customBorderColor);
            obj.put("bgAlphaPercent", bgAlphaPercent);
            obj.put("fontFamily", fontFamily);
            obj.put("isBold", isBold);
            obj.put("fontScalePercent", fontScalePercent);
            return obj.toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    public static int clamp(int val, int min, int max) {
        return Math.max(min, Math.min(max, val));
    }

    public static int parseColorSafe(String hex, int fallback) {
        if (hex == null || hex.trim().isEmpty()) return fallback;
        try {
            return Color.parseColor(hex.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private int applyAlpha(int rgbColor) {
        int alpha = Math.round(255f * clamp(bgAlphaPercent, 20, 100) / 100f);
        return Color.argb(alpha, Color.red(rgbColor), Color.green(rgbColor), Color.blue(rgbColor));
    }

    public int resolveBgColor(int sampledScreenRgb) {
        if ("outline_only".equals(boxShape)) {
            return Color.TRANSPARENT;
        }
        int baseRgb;
        switch (colorPreset) {
            case "cyber_dark":
                baseRgb = Color.parseColor("#0F172A");
                break;
            case "cinema_gold":
                baseRgb = Color.parseColor("#090D16");
                break;
            case "paper_light":
                baseRgb = Color.parseColor("#FFFFFF");
                break;
            case "royal_purple":
                baseRgb = Color.parseColor("#1E1B4B");
                break;
            case "custom":
                baseRgb = parseColorSafe(customBgColor, Color.parseColor("#0F172A"));
                break;
            case "lens_smart":
            default:
                baseRgb = sampledScreenRgb;
                break;
        }
        return applyAlpha(baseRgb);
    }

    public int resolveTextColor(int sampledScreenRgb) {
        switch (colorPreset) {
            case "cyber_dark":
                return Color.parseColor("#6EE7B7");
            case "cinema_gold":
                return Color.parseColor("#FACC15");
            case "paper_light":
                return Color.parseColor("#0F172A");
            case "royal_purple":
                return Color.parseColor("#F8FAFC");
            case "custom":
                return parseColorSafe(customTextColor, Color.WHITE);
            case "lens_smart":
            default:
                int lum = (int) (0.299 * Color.red(sampledScreenRgb)
                    + 0.587 * Color.green(sampledScreenRgb)
                    + 0.114 * Color.blue(sampledScreenRgb));
                return (lum > 145) ? Color.parseColor("#0F172A") : Color.parseColor("#F8FAFC");
        }
    }

    public int resolveBorderColor(int sampledScreenRgb) {
        switch (colorPreset) {
            case "cyber_dark":
                return Color.parseColor("#38BDF8");
            case "cinema_gold":
                return Color.parseColor("#EAB308");
            case "paper_light":
                return Color.parseColor("#64748B");
            case "royal_purple":
                return Color.parseColor("#A78BFA");
            case "custom":
                return parseColorSafe(customBorderColor, Color.parseColor("#38BDF8"));
            case "lens_smart":
            default:
                int lum = (int) (0.299 * Color.red(sampledScreenRgb)
                    + 0.587 * Color.green(sampledScreenRgb)
                    + 0.114 * Color.blue(sampledScreenRgb));
                return (lum > 145) ? Color.parseColor("#64748B") : Color.parseColor("#7DD3FC");
        }
    }

    public Typeface resolveTypeface(Context ctx) {
        String key = fontFamily + "_" + (isBold ? "B" : "R");
        if (TYPEFACE_CACHE.containsKey(key)) {
            return TYPEFACE_CACHE.get(key);
        }
        int style = isBold ? Typeface.BOLD : Typeface.NORMAL;
        String assetName;
        String fallbackFamily;
        switch (fontFamily) {
            case "naskh":
                assetName = isBold ? "fonts/NotoNaskhArabic-Bold.ttf" : "fonts/NotoNaskhArabic-Regular.ttf";
                fallbackFamily = "serif";
                break;
            case "sans":
                assetName = isBold ? "fonts/NotoSansArabic-Bold.ttf" : "fonts/NotoSansArabic-Regular.ttf";
                fallbackFamily = "sans-serif";
                break;
            case "condensed":
                assetName = "fonts/DejaVuSansCondensed-Bold.ttf";
                fallbackFamily = "sans-serif-condensed";
                break;
            case "mono":
                assetName = "fonts/DejaVuSansMono-Bold.ttf";
                fallbackFamily = "monospace";
                break;
            case "kufi":
            default:
                assetName = isBold ? "fonts/NotoKufiArabic-Bold.ttf" : "fonts/NotoKufiArabic-Regular.ttf";
                fallbackFamily = "sans-serif-medium";
                break;
        }
        Typeface tf = null;
        if (ctx != null) {
            try {
                tf = Typeface.createFromAsset(ctx.getAssets(), assetName);
            } catch (Exception ignored) {
            }
        }
        if (tf == null) {
            tf = Typeface.create(fallbackFamily, style);
        }
        TYPEFACE_CACHE.put(key, tf);
        return tf;
    }

    public void adjustFontScale(int delta) {
        fontScalePercent = clamp(fontScalePercent + delta, 70, 180);
    }

    public String cycleColorPreset() {
        String[] order = {"lens_smart", "cyber_dark", "cinema_gold", "paper_light", "royal_purple", "custom"};
        int idx = 0;
        for (int i = 0; i < order.length; i++) {
            if (order[i].equals(colorPreset)) {
                idx = (i + 1) % order.length;
                break;
            }
        }
        colorPreset = order[idx];
        return getColorLabelAr();
    }

    public String cycleBoxShape() {
        String[] order = {"lens_auto", "rounded_card", "capsule_pill", "sharp_banner", "outline_only"};
        int idx = 0;
        for (int i = 0; i < order.length; i++) {
            if (order[i].equals(boxShape)) {
                idx = (i + 1) % order.length;
                break;
            }
        }
        boxShape = order[idx];
        return getShapeLabelAr();
    }

    public String cycleFontFamily() {
        String[] order = {"kufi", "sans", "naskh", "condensed", "mono"};
        int idx = 0;
        for (int i = 0; i < order.length; i++) {
            if (order[i].equals(fontFamily)) {
                idx = (i + 1) % order.length;
                break;
            }
        }
        fontFamily = order[idx];
        return getFontLabelAr();
    }

    public String getColorLabelAr() {
        switch (colorPreset) {
            case "cyber_dark": return "داكن سماوي";
            case "cinema_gold": return "ذهبي سينمائي";
            case "paper_light": return "نهاري أبيض";
            case "royal_purple": return "بنفسجي هادئ";
            case "custom": return "لون مخصص";
            default: return "عدسة تلقائي";
        }
    }

    public String getShapeLabelAr() {
        switch (boxShape) {
            case "rounded_card": return "بطاقة مؤطرة";
            case "capsule_pill": return "كبسولة دائرية";
            case "sharp_banner": return "شريط كلاسيكي";
            case "outline_only": return "نص حر محدد";
            default: return "عدسة ناعمة";
        }
    }

    public String getFontLabelAr() {
        switch (fontFamily) {
            case "sans": return "حديث واضح";
            case "naskh": return "نسخ كلاسيكي";
            case "condensed": return "مدمج للألعاب";
            case "mono": return "تقني ثابت";
            default: return "كوفي عصري";
        }
    }
}
