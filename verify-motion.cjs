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
      // The camera settles over roughly half a second after the page stops; writes must end after that.
      await new Promise(resolve => setTimeout(resolve, 700));
      count = observer.takeRecords().length;
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
        await check(`${label}: scene stops writing styles once the camera settles`, () => assertNoIdleStyleWrites(page));
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
        if (viewport.width >= 1200) {
          await check(`${label}: hero planes part at different speeds as the page moves`, async () => {
            const at = top => page.evaluate(async y => {
              window.scrollTo({ top: y, behavior: 'instant' });
              await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));
              const read = selector => getComputedStyle(document.querySelector(selector)).transform;
              return { sky: read('.hero-layer-sky'), mid: read('.hero-image'), fore: read('.hero-layer-fore'), copy: getComputedStyle(document.querySelector('#hero-content')).opacity };
            }, top);
            const rest = await at(0);
            const moved = await at(320);
            assert.equal(new Set([moved.sky, moved.mid, moved.fore]).size, 3, 'Each plane needs its own motion');
            assert.notEqual(moved.sky, rest.sky, 'The far plane should move with the page');
            assert(Number(moved.copy) < Number(rest.copy), 'The hero copy should fade as it parts from the scene');
          });
          await check(`${label}: chapters wipe in the direction of travel with a light sweep`, async () => {
            await scrollToProgress(page, .28);
            await page.waitForTimeout(700);
            const forward = await page.evaluate(async () => {
              const journey = document.querySelector('#journey'), stage = document.querySelector('#journey-stage');
              const top = window.scrollY + journey.getBoundingClientRect().top;
              window.scrollTo({ top: top + (journey.offsetHeight - stage.offsetHeight) * .48, behavior: 'instant' });
              await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));
              return {
                direction: stage.dataset.direction,
                leaving: document.querySelector('#inspection').classList.contains('is-leaving'),
                entering: document.querySelector('#service h2').getAnimations().length,
                sweeping: document.querySelector('#car-object').classList.contains('sweeping')
              };
            });
            assert.equal(forward.direction, 'forward');
            assert.equal(forward.leaving, true, 'The previous chapter should wipe out');
            assert(forward.entering > 0, 'The next chapter should wipe in');
            assert.equal(forward.sweeping, true, 'A light sweep should cross the car');
            await page.waitForTimeout(700);
            const back = await scrollToProgress(page, .28);
            assert.equal(back.currentStep, '1');
            assert.equal(await page.locator('#journey-stage').getAttribute('data-direction'), 'back');
          });
          await check(`${label}: the reflection mirrors the car and the rail marker follows the journey`, async () => {
            const read = () => page.evaluate(() => {
              const matrix = selector => new DOMMatrixReadOnly(getComputedStyle(document.querySelector(selector)).transform);
              const reflection = matrix('#car-reflection'), car = matrix('#car-object');
              return {
                reflectionY: reflection.m22, reflectionX: reflection.m11, carX: car.m11,
                marker: matrix('#rail-marker').m42,
                railVisible: getComputedStyle(document.querySelector('.chapter-rail')).display !== 'none'
              };
            });
            await scrollToProgress(page, .2);
            await page.waitForTimeout(700);
            const first = await read();
            await scrollToProgress(page, .8);
            await page.waitForTimeout(700);
            const second = await read();
            assert(first.reflectionY < 0 && second.reflectionY < 0, 'The reflection must be mirrored');
            assert(Math.abs(Math.abs(first.reflectionX) - Math.abs(first.carX)) < .02, 'The reflection should match the car scale');
            assert(first.railVisible, 'The chapter rail should show on desktop');
            assert(second.marker > first.marker + 20, 'The rail marker should travel with the journey');
          });
        }
      } finally {
        await context.close();
      }
    }

    // Scrubbing needs HTTP range requests. The Python preview server has none, so this check runs only where the host supports them.
    const probeContext = await browser.newContext();
    const rangeProbe = await probeContext.request.get(`${baseURL}/assets/video/hub-reveal-loop.webm`, { headers: { Range: 'bytes=0-1' } });
    await probeContext.close();
    if (rangeProbe.status() !== 206) console.log('SKIP Chapter clips scrub with the journey: this server does not answer range requests (run against `npx serve dist` or the deployed site)');
    else await check('Chapter clips scrub with the journey when a manifest lists them', async () => {
      const context = await browser.newContext({ viewport: { width: 1440, height: 1000 }, reducedMotion: 'no-preference' });
      try {
        await context.addInitScript(() => {
          window.SERV60_CHAPTER_CLIPS = [{ stage: 0, webm: 'assets/video/hub-reveal-loop.webm', mp4: 'assets/video/hub-reveal-loop.mp4' }];
        });
        const page = await context.newPage();
        page.setDefaultTimeout(8000);
        page.on('pageerror', error => browserErrors.push(`clips: ${error.message}`));
        await page.goto(baseURL, { waitUntil: 'load' });
        await page.waitForFunction(() => { const video = document.querySelector('.scene-film video'); return video && video.readyState >= 1; });
        await scrollToProgress(page, .1);
        // The preview server has no range requests, so the seek lands only once the clip has buffered.
        await page.waitForFunction(() => { const video = document.querySelector('.scene-film video'); return Math.abs(video.currentTime - video.duration * .5) < .25; });
        await page.waitForFunction(() => getComputedStyle(document.querySelector('.scene-film video')).opacity === '1');
        const state = await page.evaluate(() => {
          const video = document.querySelector('.scene-film video');
          return { duration: video.duration, time: video.currentTime, opacity: getComputedStyle(video).opacity };
        });
        assert(state.duration > 9, 'The clip should report its duration');
        assert(Math.abs(state.time - state.duration * .5) < .25, `Scrub position ${state.time} of ${state.duration}`);
        assert.equal(state.opacity, '1', 'The active chapter clip should be visible');
        await scrollToProgress(page, .3);
        await page.waitForTimeout(900);
        assert.equal(await page.locator('.scene-film video').evaluate(video => getComputedStyle(video).opacity), '0', 'A clip hides outside its chapter');
      } finally {
        await context.close();
      }
    });
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
