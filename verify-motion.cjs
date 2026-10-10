/** Regression checks for scroll response, vehicle proportions, and idle motion. */
'use strict';

const assert = require('node:assert/strict');
const { chromium } = require('playwright');

const baseURL = process.env.SERV60_PREVIEW_URL || 'http://127.0.0.1:4173';
const failures = [];
let passed = 0;

async function check(label, run) {
  try {
    await run();
    passed += 1;
    console.log(`PASS ${label}`);
  } catch (error) {
    failures.push({ label, message: error.message });
    console.error(`FAIL ${label}: ${error.message}`);
  }
}

// Measure observable output after two animation frames, without a settling delay.
async function scrollToProgress(page, progress) {
  return page.evaluate(async requested => {
    const journey = document.querySelector('#journey');
    const stage = document.querySelector('#journey-stage');
    const top = window.scrollY + journey.getBoundingClientRect().top;
    const travel = journey.offsetHeight - stage.offsetHeight;
    window.scrollTo({ top: top + travel * requested, behavior: 'instant' });
    await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));
    const bar = document.querySelector('#journey-progress');
    const matrix = new DOMMatrixReadOnly(getComputedStyle(document.querySelector('#car-object')).transform);
    return {
      expectedProgress: Math.min(1, Math.max(0, (window.scrollY - top) / travel)),
      progress: bar.getBoundingClientRect().width / bar.parentElement.getBoundingClientRect().width,
      currentStep: document.querySelector('[data-go][aria-current="step"]')?.dataset.go,
      visibleChapters: [...document.querySelectorAll('[data-chapter]')]
        .filter(chapter => chapter.getAttribute('aria-hidden') === 'false')
        .map(chapter => chapter.dataset.chapter),
      documentWidth: document.documentElement.scrollWidth,
      viewportWidth: window.innerWidth,
      transform: {
        scaleX: Math.hypot(matrix.m11, matrix.m12, matrix.m13),
        scaleY: Math.hypot(matrix.m21, matrix.m22, matrix.m23),
        axesDotProduct: matrix.m11 * matrix.m21 + matrix.m12 * matrix.m22 + matrix.m13 * matrix.m23,
        serialized: matrix.toString()
      }
    };
  }, progress);
}

async function imageProportions(page) {
  return page.locator('#car-object > img').evaluateAll(images => images.map(image => {
    const style = getComputedStyle(image);
    return {
      label: 'journey',
      naturalWidth: image.naturalWidth,
      naturalHeight: image.naturalHeight,
      declaredWidth: image.getAttribute('width'),
      declaredHeight: image.getAttribute('height'),
      // CSS layout dimensions exclude transforms and rotation's bounding box.
      width: parseFloat(style.width),
      height: parseFloat(style.height)
    };
  }));
}

async function runningParticles(page) {
  return page.locator('#wash-drops').evaluate(element => element.getAnimations({ subtree: true })
    .filter(animation => animation.playState === 'running').length);
}

async function assertNoIdleStyleWrites(page) {
  const writes = await page.evaluate(async () => {
    let count = 0;
    const observer = new MutationObserver(records => { count += records.length; });
    observer.observe(document.querySelector('#car-scene'), {
      attributes: true, attributeFilter: ['style'], subtree: true
    });
    observer.observe(document.querySelector('#journey-progress'), {
      attributes: true, attributeFilter: ['style']
    });
    try {
      await new Promise(resolve => setTimeout(resolve, 180));
      count += observer.takeRecords().length;
      return count;
    } finally {
      observer.disconnect();
    }
  });
  assert.equal(writes, 0, `Scene/progress received ${writes} style mutations after scrolling stopped`);
}

(async () => {
  const browser = await chromium.launch({ headless: true, ...(process.env.SERV60_BROWSER_CHANNEL ? { channel: process.env.SERV60_BROWSER_CHANNEL } : {}) });
  const browserErrors = [];
  try {
    for (const viewport of [
      { width: 1440, height: 1000 },
      { width: 390, height: 844 },
      { width: 320, height: 568 },
      { width: 844, height: 390 }
    ]) {
      const label = `${viewport.width}×${viewport.height}`;
      const context = await browser.newContext({ viewport, reducedMotion: 'no-preference' });
      try {
        const page = await context.newPage();
        page.setDefaultTimeout(5000);
        page.on('pageerror', error => browserErrors.push(`${label}: ${error.message}`));
        await page.goto(baseURL, { waitUntil: 'load' });
        await scrollToProgress(page, .08);
        await page.locator('#car-object > img').evaluate(image => image.decode());

        await check(`${label}: natural, declared, and rendered car proportions match`, async () => {
          const images = await imageProportions(page);
          assert.equal(images.length, 1, 'A single car image should persist through the journey');
          for (const image of images) {
            assert.equal(image.naturalWidth, 1774, `${image.label}: source width`);
            assert.equal(image.naturalHeight, 887, `${image.label}: source height`);
            assert.equal(image.declaredWidth, '1774', `${image.label}: declared width`);
            assert.equal(image.declaredHeight, '887', `${image.label}: declared height`);
            assert(image.width > 0 && image.height > 0, `${image.label}: image must have a layout box`);
            assert(Math.abs(image.height - image.width / 2) <= 0.5,
              `${image.label}: distorted layout ${image.width}×${image.height}`);
          }
        });

        const samples = [];
        for (const progress of [.08, .28, .48, .68, .88, .68, .48, .28, .08]) {
          samples.push(await scrollToProgress(page, progress));
        }
        await check(`${label}: all stages track forward/reverse scroll within two frames`, async () => {
          for (const sample of samples) {
            const expectedStage = String(Math.min(4, Math.floor(sample.expectedProgress * 5)));
            assert.equal(sample.currentStep, expectedStage, `Step at progress ${sample.expectedProgress}`);
            assert.deepEqual(sample.visibleChapters, [expectedStage], 'Accessible chapter must follow scroll');
            assert(Math.abs(sample.progress - sample.expectedProgress) < .002,
              `Progress trails scroll: displayed ${sample.progress}, expected ${sample.expectedProgress}`);
          }
        });
        await check(`${label}: journey does not create horizontal page overflow`, async () => {
          for (const sample of samples) {
            assert(sample.documentWidth <= sample.viewportWidth + 1,
              `Horizontal overflow at progress ${sample.expectedProgress}: ${sample.documentWidth}px in ${sample.viewportWidth}px viewport`);
          }
        });
        await check(`${label}: car retains uniform scale throughout its journey`, async () => {
          for (const { transform } of samples) {
            assert(transform.scaleX > 0 && Number.isFinite(transform.scaleX));
            assert(Math.abs(transform.scaleX - transform.scaleY) < .00001,
              `Nonuniform vehicle scale: ${JSON.stringify(transform)}`);
            assert(Math.abs(transform.axesDotProduct) < .00001, 'Vehicle axes must not skew');
          }
          assert(new Set(samples.map(sample => sample.transform.serialized)).size >= 4,
            'Vehicle should visibly move between service stages');
        });

        await scrollToProgress(page, .48);
        await check(`${label}: scene stops writing styles when scroll stops`, () => assertNoIdleStyleWrites(page));
        await scrollToProgress(page, .68);
        await scrollToProgress(page, 1.1);
        await check(`${label}: wash particles stop below the visible journey`, async () => {
          assert.equal(await runningParticles(page), 0);
        });
        await scrollToProgress(page, .68);
        await scrollToProgress(page, -.2);
        await check(`${label}: wash particles stop above the visible journey`, async () => {
          assert.equal(await runningParticles(page), 0);
        });
        await scrollToProgress(page, .68);
        await page.emulateMedia({ reducedMotion: 'reduce' });
        await page.waitForFunction(() => !document.body.classList.contains('motion'));
        await check(`${label}: reduced motion stops particles and preserves image proportions`, async () => {
          assert.equal(await runningParticles(page), 0);
          for (const image of await imageProportions(page)) {
            assert(Math.abs(image.height - image.width / 2) <= .5, `${image.label}: reduced-motion distortion`);
          }
          assert.equal(await page.locator('[data-chapter][aria-hidden="true"]').count(), 0);
        });
        await page.emulateMedia({ reducedMotion: 'no-preference' });
        await page.waitForFunction(() => document.body.classList.contains('motion'));
        const resumed = await scrollToProgress(page, .48);
        await check(`${label}: normal motion resumes at the current scroll position`, async () => {
          assert.equal(resumed.currentStep, '2');
          assert(Math.abs(resumed.progress - resumed.expectedProgress) < .002);
        });
      } finally {
        await context.close();
      }
    }
    await check('No JavaScript errors during motion checks', async () => assert.deepEqual(browserErrors, []));
  } finally {
    await browser.close();
  }
  console.log(JSON.stringify({ passed, failed: failures.length, failures }, null, 2));
  if (failures.length) process.exitCode = 1;
})().catch(error => {
  console.error(error);
  process.exitCode = 1;
});
