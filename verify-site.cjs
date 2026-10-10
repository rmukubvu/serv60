/** End-to-end checks for the visitor experience, including motion preferences. */
const assert = require('node:assert/strict');
const fs = require('node:fs');
const { chromium } = require('playwright');
const baseURL = process.env.SERV60_PREVIEW_URL || 'http://127.0.0.1:4173';
const checks = [];
const errors = [];
function pass(message) { checks.push(message); console.log(`PASS ${message}`); }
async function moveToStage(page, index) {
  await page.evaluate(i => {
    const journey = document.querySelector('#journey');
    const stage = document.querySelector('#journey-stage');
    window.scrollTo({ top: window.scrollY + journey.getBoundingClientRect().top + (journey.offsetHeight - stage.offsetHeight) * ((i + .45) / 5), behavior: 'instant' });
  }, index);
  await page.waitForFunction(i => document.querySelector('#journey-stage').dataset.stage === String(i), index);
  await page.waitForTimeout(700);
}
async function assertFits(page, label) {
  await assertNoHorizontalOverflow(page, label);
  const box = await page.locator('.journey-bottom').boundingBox();
  assert(box.y >= 0 && box.y + box.height <= page.viewportSize().height + 1, `${label}: stage navigation is outside the viewport`);
  const copy = await page.locator('.chapter.is-active').boundingBox();
  assert(copy.y >= 0 && copy.y + copy.height < box.y - 5, `${label}: copy overlaps navigation`);
}
async function assertNoHorizontalOverflow(page, label) {
  const size = await page.evaluate(() => ({ document: document.documentElement.scrollWidth, viewport: innerWidth }));
  assert(size.document <= size.viewport + 1, `${label}: horizontal overflow ${JSON.stringify(size)}`);
}
async function assertArchitectureImages(page) {
  for (const section of ['#top', '#the-hub']) {
    const image = page.locator(`${section} img[src*="hub-architecture.webp"]`);
    assert.equal(await image.count(), 1, `${section}: the architectural vision should be visible`);
    await image.scrollIntoViewIfNeeded();
    const dimensions = await image.evaluate(async node => {
      await node.decode();
      const style = getComputedStyle(node);
      return {
        naturalWidth: node.naturalWidth,
        naturalHeight: node.naturalHeight,
        width: parseFloat(style.width),
        height: parseFloat(style.height),
        fit: style.objectFit
      };
    });
    assert(dimensions.naturalWidth > 0 && dimensions.naturalHeight > 0,
      `${section}: architecture image failed to load`);
    assert(dimensions.width > 0 && dimensions.height > 0,
      `${section}: architecture image has no visible layout box`);
    // Cropping a full-bleed photograph is intentional; stretching its pixels is not.
    const naturalRatio = dimensions.naturalWidth / dimensions.naturalHeight;
    const layoutRatio = dimensions.width / dimensions.height;
    assert(['cover', 'contain'].includes(dimensions.fit) || Math.abs(layoutRatio - naturalRatio) < .01,
      `${section}: image could be stretched (${JSON.stringify(dimensions)})`);
  }
}
async function assertEditorialSectionsFit(page, label) {
  for (const selector of ['#top', '#your-hour', '#the-hub', '.finale', '.questions']) {
    const section = page.locator(selector);
    await section.scrollIntoViewIfNeeded();
    await assertNoHorizontalOverflow(page, `${label}, ${selector}`);
    const box = await section.boundingBox();
    assert(box && box.x >= -1 && box.x + box.width <= page.viewportSize().width + 1,
      `${label}, ${selector}: section extends beyond the viewport`);
  }
}
(async () => {
  const browser = await chromium.launch({ headless: true, ...(process.env.SERV60_BROWSER_CHANNEL ? { channel: process.env.SERV60_BROWSER_CHANNEL } : {}) });
  try {
    const context = await browser.newContext({ viewport: { width: 1440, height: 1000 } });
    const page = await context.newPage();
    const requests = [];
    page.on('pageerror', error => errors.push(error.message));
    page.on('request', request => requests.push(request.url()));
    page.on('response', response => { if (response.status() >= 400) errors.push(`${response.status()}: ${response.url()}`); });
    await page.goto(baseURL, { waitUntil: 'networkidle' });
    assert.equal(await page.title(), 'Serv60 — Car care. Time well spent.');
    assert.equal(requests.some(url => url.endsWith('.mp4')), false, 'The full video must not load before a visitor requests it');
    pass('Page loads without downloading the film');
    await assertArchitectureImages(page);
    await assertEditorialSectionsFit(page, 'Desktop');
    assert.equal(await page.locator('#top .hero-car').count(), 0, 'The destination hero should not duplicate the journey car');
    pass('The architectural destination loads in the hero and hub without distortion or page overflow');
    const transforms = new Set();
    for (const index of [0, 1, 2, 3, 4, 3, 1, 0]) {
      await moveToStage(page, index);
      assert.equal(await page.locator('.chapter[aria-hidden="false"]').count(), 1);
      assert.equal(await page.locator('[aria-current="step"]').getAttribute('data-go'), String(index));
      transforms.add(await page.locator('#car-object').evaluate(node => getComputedStyle(node).transform));
      await assertFits(page, `Desktop stage ${index}`);
      const effect = ['.check-in-ticket', '.scan-mask', '.lift', '.wash-drops', '.handover-stamp'][index];
      assert(Number(await page.locator(effect).evaluate(node => getComputedStyle(node).opacity)) > .9, `${effect} not visible`);
    }
    assert(transforms.size >= 5, 'The car should move with the journey');
    assert.equal(await page.locator('#car-object img').count(), 1, 'A single persistent car should drive the journey');
    pass('All five stages work forwards and backwards with the correct visual treatment');
    for (const index of [2, 4, 0]) {
      await page.locator(`[data-go="${index}"]`).click();
      await page.waitForFunction(i => document.querySelector('#journey-stage').dataset.stage === String(i), index);
      await page.waitForTimeout(800);
    }
    pass('Chapter buttons navigate the pinned sequence');
    for (const key of ['coffee', 'barber', 'work']) {
      const opener = page.locator(`[data-experience="${key}"]`);
      await opener.click();
      assert.equal(await page.locator('#experience-dialog').evaluate(node => node.open), true);
      assert.equal(await page.locator('#experience-features li').count(), 3);
      await page.locator('#experience-image').evaluate(image => image.decode());
      await page.keyboard.press('Escape');
      assert.equal(await page.locator('#experience-dialog').evaluate(node => node.open), false);
      assert.equal(await opener.evaluate(node => document.activeElement === node), true, 'Focus should return to the card');
    }
    pass('All lifestyle panels load their own images and restore keyboard focus');
    await page.locator('#plan-visit').click();
    await page.locator('[data-choice="work"]').click();
    assert.match(await page.locator('#plan-result-title').innerText(), /Clear thinking/);
    assert.equal(await page.locator('[data-choice][aria-pressed="true"]').count(), 1);
    assert.match(await page.locator('.plan-note').innerText(), /not a booking/);
    await page.keyboard.press('Escape');
    await page.waitForFunction(() => !document.body.classList.contains('modal-open'));
    assert.equal(await page.locator('body').evaluate(node => node.classList.contains('modal-open')), false);
    pass('Visit chooser updates locally and explains that it is a concept preview');
    await page.locator('.faq-list summary').first().click();
    assert.equal(await page.locator('.faq-list details').first().getAttribute('open'), '');
    pass('FAQ expands with service eligibility and target limits');
    await page.locator('#watch-film').click();
    await page.waitForFunction(() => document.querySelector('#hub-film').readyState >= 2);
    const filmInfo = await page.locator('#hub-film').evaluate(video => ({ duration: video.duration, width: video.videoWidth, height: video.videoHeight }));
    assert(Math.abs(filmInfo.duration - 96) < 1, 'The complete simulation should be retained');
    assert.equal(filmInfo.width, 1920);
    assert.equal(filmInfo.height, 1080);
    assert(requests.some(url => url.endsWith('.mp4')));
    await page.keyboard.press('Escape');
    await page.waitForFunction(() => document.querySelector('#hub-film').paused);
    assert.equal(await page.locator('#hub-film').evaluate(video => video.paused), true);
    pass('Film plays on demand and pauses when the dialog closes');
    const originalFilm = page.locator('.film-download');
    assert.equal(await originalFilm.getAttribute('download'), 'auto-hub-operations-simulation.mp4');
    assert.equal(await originalFilm.getAttribute('href'), 'assets/auto-hub-operations-simulation.mp4');
    const originalResponse = await page.request.head(`${baseURL}/assets/auto-hub-operations-simulation.mp4`);
    assert.equal(originalResponse.status(), 200);
    const downloadSize = Number(originalResponse.headers()['content-length']);
    assert(downloadSize > 0 && downloadSize <= 26214400, 'Web film must fit the host’s per-file limit');
    pass('The complete operations simulation is linked as an on-demand MP4 download');
    for (const viewport of [{ width: 390, height: 844 }, { width: 320, height: 568 }, { width: 768, height: 1024 }, { width: 1024, height: 600 }, { width: 844, height: 390 }]) {
      await page.setViewportSize(viewport);
      await assertArchitectureImages(page);
      await assertEditorialSectionsFit(page, `${viewport.width}×${viewport.height}`);
      for (const index of [0, 1, 2, 3, 4]) {
        await moveToStage(page, index);
        await assertFits(page, `${viewport.width}×${viewport.height}, stage ${index}`);
      }
    }
    pass('The hero, lifestyle, hub and all stages fit phones, tablets, desktops and landscape screens');
    await page.setViewportSize({ width: 390, height: 844 });
    await page.emulateMedia({ reducedMotion: 'reduce' });
    await page.waitForFunction(() => !document.body.classList.contains('motion'));
    assert.equal(await page.locator('body').evaluate(node => node.classList.contains('motion')), false);
    assert.equal(await page.locator('.chapter[aria-hidden]').count(), 0);
    for (const chapter of await page.locator('.chapter').all()) assert(await chapter.isVisible());
    assert.equal(await page.locator('#journey-stage').evaluate(node => getComputedStyle(node).position), 'relative');
    await page.locator('[data-go="3"]').click();
    const wash = await page.locator('#wash').boundingBox();
    assert(wash.y >= 0 && wash.y < 844);
    await page.emulateMedia({ reducedMotion: 'no-preference' });
    await page.waitForFunction(() => document.body.classList.contains('motion'));
    await moveToStage(page, 2);
    pass('Changing reduced-motion preferences live preserves the entire story and navigation');
    const staticContext = await browser.newContext({ javaScriptEnabled: false, viewport: { width: 390, height: 844 } });
    const staticPage = await staticContext.newPage();
    await staticPage.goto(baseURL, { waitUntil: 'networkidle' });
    for (const chapter of await staticPage.locator('.chapter').all()) assert(await chapter.isVisible());
    for (const card of await staticPage.locator('[data-experience]').all()) assert(await card.isDisabled());
    assert.equal(await staticPage.locator('.film-fallback').getAttribute('href'), 'assets/auto-hub-operations-simulation.mp4');
    assert(await staticPage.locator('.film-fallback').isVisible());
    assert.equal(await staticPage.locator('#plan-visit').isVisible(), false);
    assert.equal(await staticPage.locator('#journey-stage').evaluate(node => getComputedStyle(node).position), 'relative');
    assert(await staticPage.locator('body').evaluate(() => document.documentElement.scrollWidth <= innerWidth));
    pass('The full service story remains readable without JavaScript');
    await staticContext.close();
    assert.deepEqual(errors, [], 'Browser errors or broken resources');
    pass('No JavaScript errors or missing resources');
    const result = { passed: checks.length, checks, errors };
    const outputDirectory = require('node:path').join(__dirname, 'qa-output');
    fs.mkdirSync(outputDirectory, { recursive: true });
    fs.writeFileSync(require('node:path').join(outputDirectory, 'serv60-qa.json'), JSON.stringify(result, null, 2));
    console.log(JSON.stringify(result, null, 2));
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
