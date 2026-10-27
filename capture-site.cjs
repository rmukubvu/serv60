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
  await page.goto(baseURL, { waitUntil: 'networkidle' });
  await page.waitForTimeout(3000);
  await page.screenshot({ path: path.join(outputDirectory, 'serv60-hero.png') });
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
  await page.evaluate(() => window.scrollTo({ top: 0, behavior: 'instant' }));
  await page.waitForTimeout(2600);
  await page.screenshot({ path: path.join(outputDirectory, 'serv60-mobile.png') });
  await page.evaluate(() => {
    const journey = document.querySelector('#journey'), stage = document.querySelector('#journey-stage');
    window.scrollTo({ top: window.scrollY + journey.getBoundingClientRect().top + (journey.offsetHeight - stage.offsetHeight) * .49, behavior: 'instant' });
  });
  await page.waitForTimeout(700);
  await page.screenshot({ path: path.join(outputDirectory, 'serv60-mobile-service.png') });
  console.log(JSON.stringify({ errors, screenshots: outputDirectory }));
  await browser.close();
})().catch(error => { console.error(error); process.exitCode = 1; });
