/** Regression checks for scroll response, the journey film, and idle motion. */
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
    return {
      expectedProgress: Math.min(1, Math.max(0, (window.scrollY - top) / travel)),
      progress: bar.getBoundingClientRect().width / bar.parentElement.getBoundingClientRect().width,
      currentStep: document.querySelector('[data-go][aria-current="step"]')?.dataset.go,
      visibleChapters: [...document.querySelectorAll('[data-chapter]')]
        .filter(chapter => chapter.getAttribute('aria-hidden') === 'false')
        .map(chapter => chapter.dataset.chapter),
      // The opacity the script asked for, not the value part-way through its fade.
      shownShots: [...document.querySelectorAll('#journey-film video')]
        .flatMap((video, index) => video.style.opacity === '1' ? [String(index)] : []),
      documentWidth: document.documentElement.scrollWidth,
      viewportWidth: window.innerWidth
    };
  }, progress);
}

// Where each shot is once the film has caught up with the scroll and its seeks have landed.
async function settledShots(page) {
  return page.evaluate(async () => {
    const videos = [...document.querySelectorAll('#journey-film video')];
    const start = performance.now();
    await new Promise(resolve => setTimeout(resolve, 700));
    while (performance.now() - start < 6000 && videos.some(video => video.seeking || video.readyState < 2)) {
      await new Promise(resolve => setTimeout(resolve, 50));
    }
    return videos.map(video => ({
      file: video.currentSrc.split('/').pop(),
      time: video.currentTime,
      duration: video.duration,
      shown: video.style.opacity === '1'
    }));
  });
}

async function assertNoIdleStyleWrites(page) {
  const writes = await page.evaluate(async () => {
    let count = 0;
    const observer = new MutationObserver(records => { count += records.length; });
    observer.observe(document.querySelector('#journey-film'), {
      attributes: true, attributeFilter: ['style'], subtree: true
    });
    observer.observe(document.querySelector('#journey-progress'), {
      attributes: true, attributeFilter: ['style']
    });
    try {
      // The film settles over roughly half a second after the page stops; writes must end after that.
      await new Promise(resolve => setTimeout(resolve, 700));
      count = observer.takeRecords().length;
      await new Promise(resolve => setTimeout(resolve, 180));
      count += observer.takeRecords().length;
      return count;
    } finally {
      observer.disconnect();
    }
  });
  assert.equal(writes, 0, `Film/progress received ${writes} style mutations after scrolling stopped`);
}

(async () => {
  const browser = await chromium.launch({ headless: true, ...(process.env.SERV60_BROWSER_CHANNEL ? { channel: process.env.SERV60_BROWSER_CHANNEL } : {}) });
  const browserErrors = [];
  // Seeking needs HTTP range requests. The Python preview server has none, so the scrub checks run only where the host supports them.
  const probeContext = await browser.newContext();
  const rangeProbe = await probeContext.request.get(`${baseURL}/assets/video/journey-check-in.mp4`, { headers: { Range: 'bytes=0-1' } });
  await probeContext.close();
  const canSeek = rangeProbe.status() === 206;
  if (!canSeek) console.log('SKIP The journey film scrubs with the scroll: this server does not answer range requests (run against `npx serve dist` or the deployed site)');
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

        await check(`${label}: the journey film waits until the journey comes near`, async () => {
          const before = await page.locator('#journey-film video').evaluateAll(videos => videos.map(video => video.getAttribute('src')));
          assert.deepEqual(before, [null, null, null, null, null], 'No shot should load with the hero');
          await scrollToProgress(page, -.1);
          await page.waitForFunction(() => [...document.querySelectorAll('#journey-film video')].every(video => video.getAttribute('src') && video.poster));
          const files = await page.locator('#journey-film video').evaluateAll(videos => videos.map(video => video.getAttribute('src').split('/').pop()));
          const cut = viewport.width <= 900 ? '-720.mp4' : '.mp4';
          assert.deepEqual(files, ['check-in', 'inspection', 'service', 'wash', 'handover'].map(shot => `journey-${shot}${cut}`),
            'Each chapter gets its own shot, in the cut for this screen');
        });

        const samples = [];
        for (const progress of [.08, .28, .48, .68, .88, .68, .48, .28, .08]) {
          samples.push(await scrollToProgress(page, progress));
        }
        await check(`${label}: all stages and their shots track forward/reverse scroll within two frames`, async () => {
          for (const sample of samples) {
            const expectedStage = String(Math.min(4, Math.floor(sample.expectedProgress * 5)));
            assert.equal(sample.currentStep, expectedStage, `Step at progress ${sample.expectedProgress}`);
            assert.deepEqual(sample.visibleChapters, [expectedStage], 'Accessible chapter must follow scroll');
            assert.deepEqual(sample.shownShots, [expectedStage], 'Exactly the chapter\'s own shot should show');
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
        if (canSeek) {
          await check(`${label}: the film scrubs forwards and backwards with the scroll`, async () => {
            await scrollToProgress(page, .5);
            const middle = await settledShots(page);
            assert.equal(middle[2].shown, true, 'The service shot should show half way through the journey');
            assert(Math.abs(middle[2].time - middle[2].duration * .5) < .1, `Service shot at ${middle[2].time} of ${middle[2].duration}, expected the middle`);
            // The shots either side wait on the frame they meet this one at, so the next cut is clean both ways.
            assert(middle[1].time > middle[1].duration - .1, 'The inspection shot should wait on its last frame');
            assert.equal(middle[3].time, 0, 'The wash shot should wait on its first frame');
            await scrollToProgress(page, .55);
            const later = await settledShots(page);
            await scrollToProgress(page, .45);
            const earlier = await settledShots(page);
            assert(later[2].time > middle[2].time + .2, 'Scrolling on should play the shot forwards');
            assert(earlier[2].time < middle[2].time - .2, 'Scrolling back should play it backwards');
          });
        }

        await scrollToProgress(page, .48);
        await check(`${label}: the film stops writing styles once it settles`, () => assertNoIdleStyleWrites(page));
        await scrollToProgress(page, .68);
        await page.emulateMedia({ reducedMotion: 'reduce' });
        await page.waitForFunction(() => !document.body.classList.contains('motion'));
        await check(`${label}: reduced motion swaps the film for a still in every chapter`, async () => {
          assert.equal(await page.locator('#journey-film').evaluate(film => getComputedStyle(film).display), 'none');
          const stills = await page.locator('.chapter-still').evaluateAll(images => images.map(image => ({
            display: getComputedStyle(image).display, width: image.getBoundingClientRect().width, height: image.getBoundingClientRect().height
          })));
          assert.equal(stills.length, 5, 'Each chapter should carry a still');
          for (const still of stills) {
            assert.equal(still.display, 'block');
            assert(Math.abs(still.width / still.height - 1280 / 656) < .02, `A still is distorted: ${still.width}×${still.height}`);
          }
          assert.equal(await page.locator('[data-chapter][aria-hidden="true"]').count(), 0);
          const playing = await page.locator('#journey-film video').evaluateAll(videos => videos.filter(video => !video.paused).length);
          assert.equal(playing, 0, 'Nothing should play under reduced motion');
        });
        await page.emulateMedia({ reducedMotion: 'no-preference' });
        await page.waitForFunction(() => document.body.classList.contains('motion'));
        const resumed = await scrollToProgress(page, .48);
        await check(`${label}: normal motion resumes at the current scroll position`, async () => {
          assert.equal(resumed.currentStep, '2');
          assert.deepEqual(resumed.shownShots, ['2']);
          assert(Math.abs(resumed.progress - resumed.expectedProgress) < .002);
        });
        if (viewport.width >= 1200) {
          await check(`${label}: the film scene recedes and the copy parts as the page moves`, async () => {
            const at = top => page.evaluate(async y => {
              window.scrollTo({ top: y, behavior: 'instant' });
              await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));
              return { scene: getComputedStyle(document.querySelector('#hero-scene')).transform, copy: getComputedStyle(document.querySelector('#hero-content')).opacity };
            }, top);
            const rest = await at(0);
            const moved = await at(320);
            assert.notEqual(moved.scene, rest.scene, 'The scene should move with the page');
            assert(Number(moved.copy) < Number(rest.copy), 'The hero copy should fade as it parts from the scene');
          });
          await check(`${label}: chapters wipe in the direction of travel and the film cuts with them`, async () => {
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
                shot: [...document.querySelectorAll('#journey-film video')].findIndex(video => video.style.opacity === '1')
              };
            });
            assert.equal(forward.direction, 'forward');
            assert.equal(forward.leaving, true, 'The previous chapter should wipe out');
            assert(forward.entering > 0, 'The next chapter should wipe in');
            assert.equal(forward.shot, 2, 'The film should cut to the service shot');
            await page.waitForTimeout(700);
            const back = await scrollToProgress(page, .28);
            assert.equal(back.currentStep, '1');
            assert.deepEqual(back.shownShots, ['1']);
            assert.equal(await page.locator('#journey-stage').getAttribute('data-direction'), 'back');
          });
          await check(`${label}: the rail marker follows the journey`, async () => {
            const read = () => page.evaluate(() => ({
              marker: new DOMMatrixReadOnly(getComputedStyle(document.querySelector('#rail-marker')).transform).m42,
              railVisible: getComputedStyle(document.querySelector('.chapter-rail')).display !== 'none'
            }));
            await scrollToProgress(page, .2);
            await page.waitForTimeout(700);
            const first = await read();
            await scrollToProgress(page, .8);
            await page.waitForTimeout(700);
            const second = await read();
            assert(first.railVisible, 'The chapter rail should show on desktop');
            assert(second.marker > first.marker + 20, 'The rail marker should travel with the journey');
          });
        }
      } finally {
        await context.close();
      }
    }

    await check('Save-Data keeps the film to its stills', async () => {
      const context = await browser.newContext({ viewport: { width: 1440, height: 1000 }, reducedMotion: 'no-preference' });
      try {
        await context.addInitScript(() => {
          Object.defineProperty(navigator, 'connection', { configurable: true, value: { saveData: true, effectiveType: '4g' } });
        });
        const page = await context.newPage();
        page.on('pageerror', error => browserErrors.push(`save-data: ${error.message}`));
        await page.goto(baseURL, { waitUntil: 'load' });
        const shown = await scrollToProgress(page, .3);
        await page.waitForFunction(() => [...document.querySelectorAll('#journey-film video')].every(video => video.poster));
        const sources = await page.locator('#journey-film video').evaluateAll(videos => videos.map(video => video.getAttribute('src')));
        assert.deepEqual(sources, [null, null, null, null, null], 'No shot should download on Save-Data');
        assert.deepEqual(shown.shownShots, ['1'], 'The chapter\'s still should still follow the scroll');
        assert.equal(await page.locator('#hero-video').evaluate(video => video.paused), true, 'The hero film should hold too');
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
