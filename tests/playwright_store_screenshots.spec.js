const { test, expect } = require('@playwright/test');
const path = require('path');

test.describe('Screen Translator Automated Play Store Screenshots', () => {
  test('opens the web app, translates multiple screens, and captures store screenshots', async ({ page }) => {
    await page.setViewportSize({ width: 1280, height: 840 });
    const fileUrl = 'file://' + path.resolve(__dirname, '../www/index.html');
    await page.goto(fileUrl, { waitUntil: 'domcontentloaded' });

    // 1. اللقطة الأولى: ترجمة شاشة لعبة RPG اليابانية إلى العربية
    await page.click('[data-preset="game_ja"]');
    await page.waitForTimeout(600);
    await page.screenshot({ path: 'store_screenshots/01_japanese_rpg_translated.png', fullPage: false });

    // 2. اللقطة الثانية: ترجمة المتجر الإلكتروني الإنجليزي إلى العربية
    await page.click('[data-preset="shop_en"]');
    await page.waitForTimeout(600);
    await page.screenshot({ path: 'store_screenshots/02_english_ecommerce_translated.png', fullPage: false });

    // 3. اللقطة الثالثة: ترجمة الأخبار التقنية الفرنسية إلى العربية
    await page.click('[data-preset="news_fr"]');
    await page.waitForTimeout(600);
    await page.screenshot({ path: 'store_screenshots/03_french_news_translated.png', fullPage: false });

    // 4. اللقطة الرابعة: ترجمة تذكرة الطيران الإسبانية إلى العربية
    await page.click('[data-preset="travel_es"]');
    await page.waitForTimeout(600);
    await page.screenshot({ path: 'store_screenshots/04_spanish_boarding_pass_translated.png', fullPage: false });
  });
});
