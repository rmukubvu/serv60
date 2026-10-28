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
    // The hero scene drifts continuously, so scroll without waiting for the image to hold still.
    await image.evaluate(node => node.scrollIntoView({ block: 'center', behavior: 'instant' }));
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
// The consent wording people agree to lives in the API; both pages must show exactly that text.
const consentText = fs.readFileSync(require('node:path').join(__dirname, 'server/waitlist/input.js'), 'utf8')
  .match(/CONSENT_TEXT = '([^']+)'/)[1];
// Replaces /api/waitlist so the form can be exercised against a static preview.
function mockWaitlist(page) {
  const mock = { reply: { status: 200, body: { ok: true, confirmationSent: false } }, requests: [] };
  page.route('**/api/waitlist', route => {
    mock.requests.push(route.request().postDataJSON());
    if (mock.reply === 'offline') return route.abort('internetdisconnected');
    return route.fulfill({ status: mock.reply.status, contentType: 'application/json', body: JSON.stringify(mock.reply.body) });
  });
  return mock;
}
async function fillWaitlist(page, scope) {
  await page.locator(`${scope} [name="email"]`).fill('thandi@example.com');
  await page.locator(`${scope} [name="firstName"]`).fill('Thandi');
  await page.locator(`${scope} [name="area"]`).fill('Sandton');
  await page.locator(`${scope} [name="vehicleMake"]`).fill('Toyota');
  await page.locator(`${scope} [name="vehicleModel"]`).fill('Corolla Cross');
  await page.locator(`${scope} [name="vehicleYear"]`).fill('2021');
  await page.locator(`${scope} [name="consent"]`).check();
}
async function openWaitlistFrom(page, selector, source) {
  const opener = page.locator(selector);
  await opener.scrollIntoViewIfNeeded();
  await opener.click();
  assert.equal(await page.locator('#waitlist-dialog').evaluate(node => node.open), true, `${source}: the waitlist should open in a dialog`);
  assert.equal(await page.locator('#waitlist-dialog [name="source"]').inputValue(), source);
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
    // Waitlist failures below are deliberate, mocked responses.
    page.on('response', response => { if (response.status() >= 400 && !response.url().includes('/api/waitlist')) errors.push(`${response.status()}: ${response.url()}`); });
    const waitlist = mockWaitlist(page);
    await page.goto(baseURL, { waitUntil: 'networkidle' });
    assert.equal(await page.title(), 'Serv60 — Car care. Time well spent.');
    assert.equal(requests.some(url => url.includes('auto-hub-operations-simulation')), false, 'The full simulation must not load before a visitor requests it');
    pass('Page loads without downloading the simulation');
    await page.waitForFunction(() => document.querySelector('#top').classList.contains('has-video') || document.querySelector('#hero-video').error, null, { timeout: 8000 }).catch(() => {});
    const heroFilm = await page.evaluate(() => {
      const video = document.querySelector('#hero-video');
      const layer = document.querySelector('#top .hero-layer');
      return {
        sources: [...video.querySelectorAll('source')].map(source => source.type),
        hasVideo: document.querySelector('#top').classList.contains('has-video'),
        layerVisible: getComputedStyle(layer).visibility === 'visible',
        copyVisible: document.querySelector('#hero-title').getBoundingClientRect().top < innerHeight
      };
    });
    assert.deepEqual(heroFilm.sources, ['video/webm', 'video/mp4'], 'The hero film needs a WebM and an MP4 source');
    assert.notEqual(heroFilm.hasVideo, heroFilm.layerVisible, 'Either the film plays or the layered stills stand in');
    assert(heroFilm.copyVisible, 'The hero copy must sit over the scene');
    pass(heroFilm.hasVideo ? 'The hero film plays over the layered stills' : 'The layered stills stand in for the hero film');
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
    for (const source of ['nav', 'hero', 'finale', 'faq']) {
      assert.equal(await page.locator(`[data-waitlist="${source}"]`).getAttribute('href'), 'waitlist.html', `${source}: should work without JavaScript`);
    }
    for (const [selector, source] of [['.nav-cta', 'nav'], ['[data-waitlist="hero"]', 'hero'], ['[data-waitlist="finale"]', 'finale']]) {
      await openWaitlistFrom(page, selector, source);
      await page.keyboard.press('Escape');
      assert.equal(await page.locator(selector).evaluate(node => document.activeElement === node), true, `${source}: focus should return to the call to action`);
    }
    await page.locator('[data-experience="barber"]').click();
    await page.locator('#experience-dialog [data-waitlist="experience"]').click();
    assert.equal(await page.locator('#experience-dialog').evaluate(node => node.open), false);
    assert.equal(await page.locator('#waitlist-dialog [name="source"]').inputValue(), 'experience');
    await page.keyboard.press('Escape');
    assert.equal(await page.locator('[data-experience="barber"]').evaluate(node => document.activeElement === node), true, 'Focus should return to the lifestyle card');
    await page.locator('#plan-visit').click();
    await page.locator('#plan-dialog [data-waitlist="planner"]').click();
    assert.equal(await page.locator('#plan-dialog').evaluate(node => node.open), false);
    assert.equal(await page.locator('#waitlist-dialog [name="source"]').inputValue(), 'planner');
    await page.keyboard.press('Escape');
    await page.waitForFunction(() => !document.body.classList.contains('modal-open'));
    assert.equal(await page.locator('#plan-visit').evaluate(node => document.activeElement === node), true, 'Focus should return to the visit planner button');
    pass('The nav, hero, finale, lifestyle panels and visit planner all lead into the waitlist and restore focus');
    await openWaitlistFrom(page, '[data-waitlist="hero"]', 'hero');
    const dialogForm = '#waitlist-dialog form';
    assert.equal(await page.locator(`${dialogForm} label[for="waitlist-consent"]`).innerText(), `Yes, ${consentText}`);
    await page.locator(`${dialogForm} [type="submit"]`).click();
    assert.equal(await page.locator(`${dialogForm} [name="email"]`).getAttribute('aria-invalid'), 'true');
    assert.equal(await page.locator('#waitlist-email-error').innerText(), 'Enter your email address.');
    assert.match(await page.locator('#waitlist-consent-error').innerText(), /Tick the box/);
    assert.equal(await page.locator(`${dialogForm} [name="email"]`).evaluate(node => document.activeElement === node), true, 'Focus should move to the first problem');
    await page.locator(`${dialogForm} [name="email"]`).fill('thandi@');
    await page.locator(`${dialogForm} [name="vehicleYear"]`).fill('19');
    await page.locator(`${dialogForm} [name="firstName"]`).focus();
    assert.match(await page.locator('#waitlist-email-error').innerText(), /like name@example\.com/);
    await page.locator(`${dialogForm} [type="submit"]`).click();
    assert.match(await page.locator('#waitlist-year-error').innerText(), /between 1950 and \d{4}/);
    await page.locator(`${dialogForm} [name="email"]`).fill('thandi@example.com');
    assert.equal(await page.locator(`${dialogForm} [name="email"]`).getAttribute('aria-invalid'), null, 'Fixing a field should clear its message');
    assert.equal(waitlist.requests.length, 0, 'Invalid forms must not be sent');
    pass('The waitlist form validates inline, explains each problem and sends nothing until it is fixed');
    await fillWaitlist(page, dialogForm);
    waitlist.reply = { status: 503, body: { ok: false, error: 'unavailable', message: 'The waitlist isn’t open just yet. Please try again soon.' } };
    await page.locator(`${dialogForm} [type="submit"]`).click();
    await page.waitForFunction(() => document.querySelector('#waitlist-dialog [data-form-status]').textContent.length > 0);
    assert.match(await page.locator('#waitlist-dialog [data-form-status]').innerText(), /isn’t open just yet/);
    assert.equal(await page.locator('#waitlist-dialog [data-waitlist-success]').isVisible(), false);
    waitlist.reply = { status: 422, body: { ok: false, error: 'invalid', fields: { email: 'Enter an email address like name@example.com.' } } };
    await page.locator(`${dialogForm} [type="submit"]`).click();
    await page.waitForFunction(() => document.querySelector('#waitlist-email').getAttribute('aria-invalid') === 'true');
    assert.equal(await page.locator('#waitlist-dialog [data-form-status]').innerText(), '');
    await page.locator(`${dialogForm} [name="email"]`).fill('thandi@example.com');
    waitlist.reply = 'offline';
    await page.locator(`${dialogForm} [type="submit"]`).click();
    await page.waitForFunction(() => /couldn’t reach/.test(document.querySelector('#waitlist-dialog [data-form-status]').textContent));
    assert.equal(await page.locator(`${dialogForm} [type="submit"]`).getAttribute('aria-disabled'), 'false');
    assert.equal(await page.locator('#waitlist-dialog [data-waitlist-success]').isVisible(), false);
    pass('Unavailable, rejected and offline submissions keep the form and say what happened, never claiming success');
    waitlist.reply = { status: 200, body: { ok: true, confirmationSent: true } };
    await page.locator(`${dialogForm} [type="submit"]`).click();
    await page.locator('#waitlist-dialog [data-waitlist-success]').waitFor();
    assert.equal(await page.locator('#waitlist-dialog [data-success-heading]').innerText(), 'You’re on the list.');
    assert.equal(await page.locator('#waitlist-dialog [data-success-heading]').evaluate(node => document.activeElement === node), true, 'Focus should move to the confirmation');
    assert.equal(await page.locator('#waitlist-dialog [data-success-email]').innerText(), 'thandi@example.com');
    assert(await page.locator('#waitlist-dialog [data-success-confirmation]').isVisible());
    assert.equal(await page.locator(dialogForm).isVisible(), false);
    assert.deepEqual(waitlist.requests.at(-1), { email: 'thandi@example.com', firstName: 'Thandi', area: 'Sandton', vehicleMake: 'Toyota', vehicleModel: 'Corolla Cross', vehicleYear: '2021', consent: true, source: 'hero' });
    assert.doesNotMatch(await page.locator('#waitlist-dialog').innerText(), /R250|money back|guarantee/i, 'The guarantee is not advertised before the soft launch');
    await page.locator('#waitlist-dialog [data-waitlist-success] [data-close]').click();
    assert.equal(await page.locator('#waitlist-dialog').evaluate(node => node.open), false);
    pass('A successful sign-up shows “You’re on the list”, moves focus to it and sends only the form’s fields');
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
      assert.equal(await page.locator('.chapter-rail').isVisible(), viewport.width > 900, `${viewport.width}: the chapter rail shows only where there is room`);
    }
    pass('The hero, lifestyle, hub and all stages fit phones, tablets, desktops and landscape screens');
    // Reload so the dialog shows the full form again rather than the earlier confirmation.
    await page.reload({ waitUntil: 'networkidle' });
    for (const viewport of [{ width: 390, height: 844 }, { width: 320, height: 568 }]) {
      await page.setViewportSize(viewport);
      await page.evaluate(() => window.scrollTo({ top: 0, behavior: 'instant' }));
      await page.locator('[data-waitlist="hero"]').click();
      const fit = await page.locator('#waitlist-dialog').evaluate(dialog => {
        const box = dialog.getBoundingClientRect();
        return { left: box.left, right: box.right, top: box.top, bottom: box.bottom, scrollWidth: dialog.scrollWidth, clientWidth: dialog.clientWidth };
      });
      assert(fit.left >= 0 && fit.right <= viewport.width && fit.top >= 0 && fit.bottom <= viewport.height, `${viewport.width}×${viewport.height}: dialog outside the viewport ${JSON.stringify(fit)}`);
      assert(fit.scrollWidth <= fit.clientWidth + 1, `${viewport.width}×${viewport.height}: dialog scrolls sideways`);
      await page.locator('#waitlist-dialog [type="submit"]').scrollIntoViewIfNeeded();
      const submit = await page.locator('#waitlist-dialog [type="submit"]').boundingBox();
      assert(submit.y >= 0 && submit.y + submit.height <= viewport.height, `${viewport.width}×${viewport.height}: the submit button cannot be reached`);
      await page.keyboard.press('Escape');
      await assertNoHorizontalOverflow(page, `${viewport.width}×${viewport.height} after the waitlist`);
    }
    pass('The waitlist dialog fits small phones and scrolls within itself');
    await page.setViewportSize({ width: 390, height: 844 });
    await page.emulateMedia({ reducedMotion: 'reduce' });
    await page.waitForFunction(() => !document.body.classList.contains('motion'));
    assert.equal(await page.locator('body').evaluate(node => node.classList.contains('motion')), false);
    assert.equal(await page.locator('.chapter[aria-hidden]').count(), 0);
    for (const chapter of await page.locator('.chapter').all()) assert(await chapter.isVisible());
    assert.equal(await page.locator('#car-reflection').isVisible(), false, 'Reduced motion drops the reflection');
    assert.equal(await page.locator('#top').evaluate(node => node.classList.contains('has-video')), false, 'Reduced motion stops the hero film');
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
    assert.equal(await staticPage.locator('.hero-layer-sky').isVisible(), false, 'Without JavaScript the hero shows one still');
    assert.equal(await staticPage.locator('#hero-video').isVisible(), false);
    assert(await staticPage.locator('#top .hero-image').isVisible());
    assert.equal(await staticPage.locator('#journey-stage').evaluate(node => getComputedStyle(node).position), 'relative');
    assert(await staticPage.locator('body').evaluate(() => document.documentElement.scrollWidth <= innerWidth));
    pass('The full service story remains readable without JavaScript');
    assert.equal(await staticPage.locator('#waitlist-dialog').isVisible(), false);
    assert(await staticPage.locator('[data-waitlist="hero"]').isVisible());
    await staticPage.locator('[data-waitlist="hero"]').click();
    await staticPage.waitForURL('**/waitlist.html');
    const staticForm = staticPage.locator('form[data-waitlist-form]');
    assert.equal(await staticForm.getAttribute('action'), '/api/waitlist');
    assert.equal(await staticForm.getAttribute('method'), 'post');
    assert.equal(await staticPage.locator('[name="email"]').getAttribute('type'), 'email');
    assert.equal(await staticPage.locator('[name="source"]').inputValue(), 'page');
    assert.equal(await staticPage.locator('label[for="waitlist-consent"]').innerText(), `Yes, ${consentText}`);
    assert.equal(await staticForm.evaluate(form => form.noValidate), false, 'Browsers should validate the form when JavaScript is off');
    await staticPage.locator('[type="submit"]').click();
    assert.equal(await staticPage.locator('[name="email"]').evaluate(input => input.validity.valueMissing), true);
    await staticPage.locator('[name="email"]').fill('pieter@example.com');
    assert.equal(await staticPage.locator('[name="consent"]').evaluate(input => input.validity.valueMissing), true);
    assert.match(await staticPage.locator('#privacy').innerText(), /unsubscribe/i);
    assert(await staticPage.locator('body').evaluate(() => document.documentElement.scrollWidth <= innerWidth));
    pass('Without JavaScript, every waitlist link reaches a validated form that posts straight to the API');
    await staticContext.close();
    const pageContext = await browser.newContext({ viewport: { width: 1440, height: 1000 } });
    const waitlistPage = await pageContext.newPage();
    waitlistPage.on('pageerror', error => errors.push(error.message));
    const pageMock = mockWaitlist(waitlistPage);
    await waitlistPage.goto(`${baseURL}/waitlist.html`, { waitUntil: 'networkidle' });
    assert.equal(await waitlistPage.title(), 'Join the waitlist — Serv60');
    await fillWaitlist(waitlistPage, 'main');
    await waitlistPage.locator('main [type="submit"]').click();
    await waitlistPage.locator('main [data-waitlist-success]').waitFor();
    assert.equal(pageMock.requests[0].source, 'page');
    assert.equal(await waitlistPage.locator('main [data-success-confirmation]').isVisible(), false);
    assert.equal(await waitlistPage.locator('main [data-waitlist-success] a').getAttribute('href'), 'index.html');
    await pageContext.close();
    pass('The waitlist page also signs people up in place when JavaScript is on');
    assert.deepEqual(errors, [], 'Browser errors or broken resources');
    pass('No JavaScript errors or missing resources');
    const result = { passed: checks.length, checks, errors };
    const outputDirectory = require('node:path').join(__dirname, 'qa-output');
    fs.mkdirSync(outputDirectory, { recursive: true });
    fs.writeFileSync(require('node:path').join(outputDirectory, 'serv60-qa.json'), JSON.stringify(result, null, 2));
    console.log(JSON.stringify(result, null, 2));
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
