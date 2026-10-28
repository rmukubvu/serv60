const { chromium } = require('playwright');
const fs = require('node:fs');
const path = require('node:path');
const outputDirectory = path.join(__dirname, 'qa-output');
const baseURL = process.env.SERV60_PREVIEW_URL || 'http://127.0.0.1:4173';
(async () => {
  fs.mkdirSync(outputDirectory, { recursive: true });
  const browser = await chromium.launch({ headless: true, ...(process.env.SERV60_BROWSER_CHANNEL ? { channel: process.env.SERV60_BROWSER_CHANNEL } : {}) });
  const page = await browser.newPage({ viewport: { width: 1440, height: 1000 }, deviceScaleFactor: 1 });
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  page.on('response', response => { if (response.status() >= 400) errors.push(`${response.status()} ${response.url()}`); });
  // The static preview has no API, so the confirmation is mocked.
  await page.route('**/api/waitlist', route => route.fulfill({ contentType: 'application/json', body: JSON.stringify({ ok: true, confirmationSent: true }) }));
  await page.goto(baseURL, { waitUntil: 'networkidle' });
  await page.waitForTimeout(3000);
  await page.screenshot({ path: path.join(outputDirectory, 'serv60-hero.png') });
  await page.locator('[data-waitlist="hero"]').click();
  await page.locator('#waitlist-dialog .waitlist-image').evaluate(image => image.decode());
  await page.screenshot({ path: path.join(outputDirectory, 'serv60-waitlist-dialog.png') });
  await page.locator('#waitlist-dialog [type="submit"]').click();
  await page.screenshot({ path: path.join(outputDirectory, 'serv60-waitlist-errors.png') });
  await page.locator('#waitlist-email').fill('thandi@example.com');
  await page.locator('#waitlist-consent').check();
  await page.locator('#waitlist-dialog [type="submit"]').click();
  await page.locator('#waitlist-dialog [data-waitlist-success]').waitFor();
  await page.screenshot({ path: path.join(outputDirectory, 'serv60-waitlist-success.png') });
  await page.keyboard.press('Escape');
  await page.evaluate(() => window.scrollTo({ top: 320, behavior: 'instant' }));
  await page.waitForTimeout(400);
  await page.screenshot({ path: path.join(outputDirectory, 'serv60-hero-parted.png') });
  for (let i = 0; i < 5; i++) {
    await page.evaluate(index => {
      const journey = document.querySelector('#journey'), stage = document.querySelector('#journey-stage');
      const top = window.scrollY + journey.getBoundingClientRect().top;
      window.scrollTo({ top: top + (journey.offsetHeight - stage.offsetHeight) * ((index + .45) / 5), behavior: 'instant' });
    }, i);
    await page.waitForTimeout(700);
    await page.screenshot({ path: path.join(outputDirectory, `serv60-stage-${i}.png`) });
  }
  await page.locator('#your-hour').scrollIntoViewIfNeeded();
  await page.waitForTimeout(1000);
  await page.screenshot({ path: path.join(outputDirectory, 'serv60-lifestyle.png') });
  for (const section of ['#the-hub', '.finale', '.questions']) {
    await page.locator(section).scrollIntoViewIfNeeded();
    await page.waitForTimeout(900);
    await page.screenshot({ path: path.join(outputDirectory, `serv60-${section.slice(1)}.png`) });
  }
  await page.setViewportSize({ width: 390, height: 844 });
  await page.evaluate(() => { document.activeElement?.blur(); window.scrollTo({ top: 0, behavior: 'instant' }); });
  await page.waitForTimeout(2600);
  await page.screenshot({ path: path.join(outputDirectory, 'serv60-mobile.png') });
  // Reload for a fresh form rather than the earlier confirmation.
  await page.reload({ waitUntil: 'networkidle' });
  await page.locator('[data-waitlist="hero"]').click();
  await page.waitForTimeout(300);
  await page.screenshot({ path: path.join(outputDirectory, 'serv60-mobile-waitlist-dialog.png') });
  await page.keyboard.press('Escape');
  await page.goto(`${baseURL}/waitlist.html`, { waitUntil: 'networkidle' });
  await page.screenshot({ path: path.join(outputDirectory, 'serv60-mobile-waitlist-page.png'), fullPage: true });
  await page.goto(baseURL, { waitUntil: 'networkidle' });
  await page.evaluate(() => {
    const journey = document.querySelector('#journey'), stage = document.querySelector('#journey-stage');
    window.scrollTo({ top: window.scrollY + journey.getBoundingClientRect().top + (journey.offsetHeight - stage.offsetHeight) * .49, behavior: 'instant' });
  });
  await page.waitForTimeout(700);
  await page.screenshot({ path: path.join(outputDirectory, 'serv60-mobile-service.png') });
  console.log(JSON.stringify({ errors, screenshots: outputDirectory }));
  await browser.close();
})().catch(error => { console.error(error); process.exitCode = 1; });
