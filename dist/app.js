(() => {
  'use strict';
  const body = document.body;
  const reducedMotion = window.matchMedia('(prefers-reduced-motion: reduce)');
  const compact = window.matchMedia('(max-width: 900px)');
  const hero = document.querySelector('#top');
  const heroContent = document.querySelector('#hero-content');
  const heroLayers = {
    sky: hero.querySelector('.hero-layer-sky'),
    mid: hero.querySelector('.hero-image'),
    fore: hero.querySelector('.hero-layer-fore'),
    video: hero.querySelector('#hero-video')
  };
  const journey = document.querySelector('#journey');
  const stage = document.querySelector('#journey-stage');
  const scene = document.querySelector('#car-scene');
  const world = document.querySelector('#scene-world');
  const chapters = [...document.querySelectorAll('[data-chapter]')];
  const stageButtons = [...document.querySelectorAll('[data-go]')];
  const progressBar = document.querySelector('#journey-progress');
  const railMarker = document.querySelector('#rail-marker');
  const chapterCount = document.querySelector('#chapter-count');
  const car = document.querySelector('#car-object');
  const shadow = scene.querySelector('.car-shadow');
  const contact = scene.querySelector('#car-contact');
  const reflection = scene.querySelector('#car-reflection');
  const ticket = scene.querySelector('.check-in-ticket');
  const scan = scene.querySelector('.scan-bar');
  const liftTop = scene.querySelector('.lift-top');
  const rain = scene.querySelector('.wash-drops');
  const effects = {
    inspection: [...scene.querySelectorAll('.scan-mask, .inspection-target')],
    service: [...scene.querySelectorAll('.lift, .service-label')],
    wash: [...scene.querySelectorAll('.wash-drops, .wash-sheen, .car-sparkle')],
    handover: [scene.querySelector('.handover-stamp')]
  };
  const parallaxItems = [...document.querySelectorAll('[data-parallax]')]
    .map(element => ({ element, amount: Number(element.dataset.parallax) || 0, top: 0, height: 0 }));
  const animatedElements = [
    car, shadow, contact, reflection, world, ticket, scan, liftTop, progressBar, railMarker,
    heroLayers.sky, heroLayers.mid, heroLayers.fore, heroLayers.video, heroContent,
    ...parallaxItems.map(item => item.element), ...Object.values(effects).flat()
  ].filter(Boolean);
  const writtenStyles = new WeakMap();
  const clamp = (value, min = 0, max = 1) => Math.min(max, Math.max(min, value));
  const mix = (a, b, amount) => a + (b - a) * amount;
  const ease = value => value * value * (3 - 2 * value);
  let activeChapter = -1;
  let frame = 0;
  let lastFrameTime = 0;
  let rawProgress = 0;         // where the page actually is: drives chapters, the rail and the bar
  let cameraProgress = -1;     // where the camera is: follows rawProgress with a short lag
  let renderedRaw = -1;
  let scrollTop = 0;
  let renderedScroll = -1;
  let journeyTop = 0;
  let journeyTravel = 1;
  let heroHeight = 1;
  let carHeight = 0;
  let sceneHeight = 0;
  let railTravel = 0;
  let viewportHeight = 1;
  const leaveTimers = new Map();
  let sweepTimer = 0;

  // Transform keyframes are expressed in percentages of the same car image.
  // The scroll position is the only clock: scrolling backwards reverses the care.
  const keyframes = [
    [0, -8, 0, .94, -2], [.16, 0, 0, 1, 0], [.28, -1, -1, 1.01, 0],
    [.40, -1, -2, 1, 0], [.49, -1, -16, .98, 0], [.58, -1, -16, .98, 0],
    [.65, 0, 0, 1, 0], [.77, 1, 0, 1.02, -1], [.86, 1, 0, 1.02, -1],
    [.92, 8, 0, 1.02, -1], [1, 74, -2, .96, -2]
  ];
  function interpolate(progress) {
    let next = keyframes.findIndex(point => point[0] >= progress);
    if (next <= 0) return keyframes[0].slice(1);
    const start = keyframes[next - 1], end = keyframes[next];
    const amount = ease(clamp((progress - start[0]) / (end[0] - start[0])));
    return start.slice(1).map((value, i) => mix(value, end[i + 1], amount));
  }
  function windowOpacity(progress, start, end, fade = .035) {
    return Math.min(clamp((progress - start) / fade), clamp((end - progress) / fade));
  }
  // Write only changed compositor properties, on the element that uses them.
  // Updating inherited custom properties on the whole scene made every child recalculate.
  function setStyle(element, property, value) {
    if (!element) return;
    // Browsers normalize CSS strings ("1.000" becomes "1"), so compare our
    // last authored value instead of rereading the serialized CSS declaration.
    let values = writtenStyles.get(element);
    if (!values) {
      values = new Map();
      writtenStyles.set(element, values);
    }
    if (values.get(property) === value) return;
    element.style[property] = value;
    values.set(property, value);
  }
  function setOpacity(elements, value) {
    const opacity = value.toFixed(3);
    elements.forEach(element => setStyle(element, 'opacity', opacity));
  }

  // Chapters: the leaving copy wipes out in the direction of travel while the next wipes in.
  function setChapter(index) {
    if (activeChapter === index) return;
    const previous = activeChapter;
    activeChapter = index;
    stage.dataset.stage = String(index);
    stage.dataset.direction = previous >= 0 && index < previous ? 'back' : 'forward';
    chapterCount.textContent = `0${index + 1} / 05`;
    chapters.forEach((chapter, i) => {
      const leaving = i === previous && !reducedMotion.matches;
      chapter.classList.toggle('is-active', i === index);
      chapter.classList.toggle('is-leaving', leaving);
      clearTimeout(leaveTimers.get(chapter));
      if (leaving) leaveTimers.set(chapter, setTimeout(() => chapter.classList.remove('is-leaving'), 620));
      if (reducedMotion.matches) chapter.removeAttribute('aria-hidden');
      else chapter.setAttribute('aria-hidden', String(i !== index));
    });
    stageButtons.forEach((button, i) => {
      if (i === index) button.setAttribute('aria-current', 'step');
      else button.removeAttribute('aria-current');
    });
    if (previous >= 0 && !reducedMotion.matches) {
      // A light sweep crosses the bodywork as the lighting changes between bays.
      car.classList.remove('sweeping');
      void car.offsetWidth;
      car.classList.add('sweeping');
      clearTimeout(sweepTimer);
      sweepTimer = setTimeout(() => car.classList.remove('sweeping'), 1300);
    }
  }

  // Deterministic parts follow the scroll position exactly: chapter, count, rail and bar.
  function renderTimeline(progress) {
    setStyle(progressBar, 'transform', `scaleX(${Math.max(.015, progress).toFixed(4)})`);
    setStyle(railMarker, 'transform', `translate3d(0,${(progress * railTravel).toFixed(2)}px,0)`);
    setChapter(Math.min(4, Math.floor(progress * 5)));
    renderedRaw = progress;
  }

  // The camera follows with a short lag, so wheel steps become one continuous move.
  function renderCamera(progress) {
    const [x, y, scale, rotation] = interpolate(progress);
    const lifted = clamp(-y / 16);
    setStyle(car, 'transform', `translate3d(${x.toFixed(3)}%,${y.toFixed(3)}%,0) scale(${scale.toFixed(4)}) rotate(${rotation.toFixed(3)}deg)`);
    setStyle(reflection, 'transform', `translate3d(${x.toFixed(3)}%,${(-y).toFixed(3)}%,0) scale(${scale.toFixed(4)}) rotate(${(-rotation).toFixed(3)}deg) scaleY(-1)`);
    setStyle(shadow, 'transform', `translate3d(${(x / .8).toFixed(3)}%,0,0) scale(${(scale * (1 + .18 * lifted)).toFixed(4)})`);
    setOpacity([shadow], .65 - .35 * lifted);
    setStyle(contact, 'transform', `translate3d(${x.toFixed(3)}%,0,0) scale(${scale.toFixed(4)})`);
    setOpacity([contact], .9 - .85 * lifted);
    // The world counters the car: a pan with the vehicle and a gentle dolly on each lift.
    setStyle(world, 'transform', `translate3d(${(-x * .28).toFixed(3)}%,${(lifted * 1.2).toFixed(3)}%,0) scale(${(1 + (scale - 1) * .6 + lifted * .025).toFixed(4)})`);
    // A wet floor in the wash and a polished floor at handover show more of the car.
    const reflect = .14 + .3 * windowOpacity(progress, .6, .82, .06) + .16 * clamp((progress - .8) / .06);
    setOpacity([reflection], reflect);
    const ticketOpacity = 1 - clamp((progress - .14) / .045);
    setOpacity([ticket], ticketOpacity);
    setStyle(ticket, 'transform', `translate3d(0,${((1 - ticketOpacity) * 22).toFixed(2)}px,0) rotate(6deg)`);
    setOpacity(effects.inspection, windowOpacity(progress, .19, .41));
    setOpacity(effects.service, windowOpacity(progress, .405, .625));
    setOpacity(effects.wash, windowOpacity(progress, .605, .81));
    setOpacity(effects.handover, clamp((progress - .805) / .055));
    // The scan travels within a fixed mask, without changing layout coordinates.
    const scanPosition = mix(40, 940, clamp((progress - .2) / .2));
    setStyle(scan, 'transform', `translate3d(${scanPosition.toFixed(2)}%,0,0)`);
    // Keep the platform under the tyres at every responsive size.
    const liftOffset = (.8 + .11 * scale + y / 100) * carHeight - .73 * sceneHeight;
    setStyle(liftTop, 'transform', `translate3d(0,${liftOffset.toFixed(2)}px,0)`);
    renderClips(progress);
    cameraProgress = progress;
  }

  // The hero is three planes of one photograph; they part as the page moves.
  function renderHero(top) {
    const depth = compact.matches ? .5 : 1;
    const p = clamp(top / heroHeight);
    if (top > heroHeight * 1.1 && renderedScroll > heroHeight * 1.1) return;
    setStyle(heroLayers.sky, 'transform', `translate3d(0,${(top * .32 * depth).toFixed(2)}px,0) scale(${(1 + .14 * p).toFixed(4)})`);
    const mid = `translate3d(0,${(top * .14 * depth).toFixed(2)}px,0) scale(${(1 + .06 * p).toFixed(4)})`;
    setStyle(heroLayers.mid, 'transform', mid);
    setStyle(heroLayers.video, 'transform', mid);
    setStyle(heroLayers.fore, 'transform', `translate3d(0,${(-top * .06 * depth).toFixed(2)}px,0) scale(${(1 + .02 * p).toFixed(4)})`);
    setStyle(heroContent, 'transform', `translate3d(0,${(top * .28 * depth).toFixed(2)}px,0)`);
    setOpacity([heroContent], 1 - clamp(top / (heroHeight * .72)));
  }

  // Editorial images drift inside their frames at a different speed from their captions.
  function renderParallax(top) {
    parallaxItems.forEach(item => {
      const centre = item.top + item.height / 2 - top;
      if (centre < -item.height || centre > viewportHeight + item.height) return;
      const delta = (centre - viewportHeight / 2) / viewportHeight;
      setStyle(item.element, 'translate', `0 ${(-delta * item.amount * 2).toFixed(2)}px`);
    });
  }

  function tick(now) {
    const elapsed = lastFrameTime ? Math.min(64, now - lastFrameTime) : 16;
    lastFrameTime = now;
    frame = 0;
    if (rawProgress !== renderedRaw) renderTimeline(rawProgress);
    if (scrollTop !== renderedScroll) {
      renderHero(scrollTop);
      renderParallax(scrollTop);
      renderedScroll = scrollTop;
    }
    if (cameraProgress !== rawProgress) {
      const lag = 1 - Math.exp(-elapsed / 85);
      let next = cameraProgress < 0 ? rawProgress : cameraProgress + (rawProgress - cameraProgress) * lag;
      if (Math.abs(rawProgress - next) < .0004) next = rawProgress;
      renderCamera(next);
    }
    if (cameraProgress !== rawProgress) frame = requestAnimationFrame(tick);
    else lastFrameTime = 0;
  }
  function schedule() {
    if (!frame) frame = requestAnimationFrame(tick);
  }
  function onScroll() {
    if (reducedMotion.matches) return;
    scrollTop = window.scrollY;
    rawProgress = clamp((scrollTop - journeyTop) / journeyTravel);
    if (rawProgress !== renderedRaw || scrollTop !== renderedScroll || cameraProgress !== rawProgress) schedule();
  }
  function measure() {
    // Geometry reads happen only on layout changes, never in the scroll renderer.
    const top = window.scrollY;
    viewportHeight = window.innerHeight;
    heroHeight = Math.max(1, hero.offsetHeight);
    journeyTop = top + journey.getBoundingClientRect().top;
    journeyTravel = Math.max(1, journey.offsetHeight - stage.offsetHeight);
    carHeight = car.offsetHeight;
    sceneHeight = scene.offsetHeight;
    const rail = railMarker ? railMarker.parentElement : null;
    railTravel = rail ? Math.max(0, rail.offsetHeight - railMarker.offsetHeight) : 0;
    parallaxItems.forEach(item => {
      const rect = item.element.getBoundingClientRect();
      item.top = rect.top + top;
      item.height = rect.height;
    });
    const rainTravel = rain.offsetHeight + 72;
    rain.style.setProperty('--rain-travel', `${rainTravel}px`);
    renderedRaw = -1;
    renderedScroll = -1;
    cameraProgress = -1;
    onScroll();
  }
  function configureMotion() {
    if (frame) cancelAnimationFrame(frame);
    frame = 0;
    lastFrameTime = 0;
    body.classList.toggle('motion', !reducedMotion.matches);
    chapters.forEach(chapter => {
      chapter.removeAttribute('aria-hidden');
      chapter.classList.remove('is-leaving');
    });
    car.classList.remove('sweeping');
    activeChapter = -1;
    animatedElements.forEach(element => {
      element.removeAttribute('style');
      writtenStyles.delete(element);
    });
    renderedRaw = -1;
    renderedScroll = -1;
    cameraProgress = -1;
    configureHeroVideo();
    if (reducedMotion.matches) {
      stage.dataset.stage = '0';
      stage.dataset.direction = 'forward';
      progressBar.style.transform = 'scaleX(1)';
      stageButtons.forEach(button => button.removeAttribute('aria-current'));
      chapterCount.textContent = '01 — 05';
    } else {
      measure();
      renderTimeline(rawProgress);
      renderCamera(rawProgress);
      renderHero(scrollTop);
      renderParallax(scrollTop);
      renderedScroll = scrollTop;
    }
  }

  // The hero film: a muted loop over the stills, shown only once it is actually playing.
  // It is fetched only after the page has painted, so it never competes with the hero still.
  let heroVideoReady = false;
  let heroFilmStarted = false;
  function startHeroFilm() {
    const video = heroLayers.video;
    if (heroFilmStarted || reducedMotion.matches) return;
    heroFilmStarted = true;
    video.preload = 'auto';
    video.load();
    video.play().catch(() => { /* The layered stills stay in place when autoplay is refused. */ });
  }
  function configureHeroVideo() {
    const video = heroLayers.video;
    if (!video) return;
    const connection = navigator.connection || {};
    const slow = !!connection.saveData || /(^|-)2g$|^3g$/.test(connection.effectiveType || '');
    if (reducedMotion.matches || slow) {
      video.pause();
      hero.classList.remove('has-video');
      return;
    }
    if (!heroVideoReady) {
      heroVideoReady = true;
      video.addEventListener('playing', () => { if (!reducedMotion.matches) hero.classList.add('has-video'); });
      const sources = video.querySelectorAll('source');
      if (sources.length) sources[sources.length - 1].addEventListener('error', () => hero.classList.remove('has-video'));
      video.addEventListener('error', () => hero.classList.remove('has-video'));
      new IntersectionObserver(entries => {
        if (reducedMotion.matches || !heroFilmStarted) return;
        if (entries[0].isIntersecting) video.play().catch(() => {});
        else video.pause();
      }).observe(hero);
      const start = () => setTimeout(startHeroFilm, 700);
      if (document.readyState === 'complete') start();
      else window.addEventListener('load', start, { once: true });
      return;
    }
    if (heroFilmStarted) video.play().catch(() => {});
    else if (document.readyState === 'complete') startHeroFilm();
  }

  // Chapter clips: scroll-scrubbed video behind the car, when a manifest lists them.
  // Each entry: { stage: 0..4, webm, mp4, poster }. Without a manifest nothing is requested.
  const clips = new Map();
  function configureClips() {
    const manifest = Array.isArray(window.SERV60_CHAPTER_CLIPS) ? window.SERV60_CHAPTER_CLIPS : [];
    if (!manifest.length || reducedMotion.matches || clips.size) return;
    const film = document.createElement('div');
    film.className = 'scene-film';
    film.setAttribute('aria-hidden', 'true');
    manifest.forEach(clip => {
      const video = document.createElement('video');
      video.muted = true;
      video.playsInline = true;
      video.preload = 'auto';
      if (clip.poster) video.poster = clip.poster;
      [['webm', 'video/webm'], ['mp4', 'video/mp4']].forEach(([key, type]) => {
        if (!clip[key]) return;
        const source = document.createElement('source');
        source.src = clip[key];
        source.type = type;
        video.append(source);
      });
      const entry = { video, ready: false, requested: -1 };
      video.addEventListener('loadedmetadata', () => { entry.ready = true; schedule(); });
      // A seek requested before the data was buffered is dropped by the browser; ask again once it can play.
      video.addEventListener('canplay', () => { entry.requested = -1; if (cameraProgress >= 0) renderClips(cameraProgress); });
      film.append(video);
      clips.set(Number(clip.stage), entry);
      animatedElements.push(video);
      video.load();
    });
    scene.prepend(film);
  }
  function renderClips(progress) {
    if (!clips.size) return;
    const index = Math.min(4, Math.floor(progress * 5));
    const local = clamp(progress * 5 - index);
    clips.forEach((clip, clipStage) => {
      const active = clipStage === index && clip.ready;
      setOpacity([clip.video], active ? 1 : 0);
      if (!active) return;
      // Track what was asked for, not what the decoder reports: a new seek simply replaces a pending one.
      const time = local * (clip.video.duration || 0);
      if (Math.abs(clip.requested - time) > 1 / 48) {
        clip.requested = time;
        clip.video.currentTime = time;
      }
    });
  }

  body.classList.add('js');
  document.querySelectorAll('[data-experience], [data-go], #watch-film, #plan-visit')
    .forEach(button => { button.disabled = false; });
  configureClips();
  configureMotion();
  reducedMotion.addEventListener('change', configureMotion);
  compact.addEventListener('change', measure);
  window.addEventListener('scroll', onScroll, { passive: true });
  window.addEventListener('resize', measure, { passive: true });
  window.addEventListener('load', measure, { once: true });
  // Once the opening reveals have had their time, pin the hero copy and header in place.
  window.addEventListener('load', () => setTimeout(() => body.classList.add('is-settled'), 3200), { once: true });
  window.addEventListener('pageshow', measure);
  new ResizeObserver(measure).observe(document.querySelector('.manifesto'));
  new IntersectionObserver(entries => {
    stage.classList.toggle('is-onscreen', entries[0].isIntersecting);
  }).observe(journey);

  stageButtons.forEach(button => button.addEventListener('click', () => {
    const index = Number(button.dataset.go);
    if (reducedMotion.matches) chapters[index].scrollIntoView({ block: 'center' });
    else {
      measure();
      window.scrollTo({ top: journeyTop + journeyTravel * ((index + .38) / 5), behavior: 'smooth' });
    }
  }));
  // Deterministic drops: no random layout changes and no animation outside the wash.
  const drops = document.createDocumentFragment();
  for (let i = 0; i < 36; i++) {
    const drop = document.createElement('i');
    drop.style.setProperty('--x', `${(i * 37) % 100}%`);
    drop.style.setProperty('--duration', `${.65 + (i % 7) * .12}s`);
    drop.style.setProperty('--delay', `${-(i % 11) * .14}s`);
    drops.append(drop);
  }
  document.querySelector('#wash-drops').append(drops);
  const revealObserver = new IntersectionObserver(entries => {
    entries.forEach(entry => {
      if (entry.isIntersecting) {
        entry.target.classList.add('is-visible');
        revealObserver.unobserve(entry.target);
      }
    });
  }, { threshold: .08 });
  document.querySelectorAll('.reveal').forEach(element => revealObserver.observe(element));

  const experiences = {
    coffee: {
      kicker: 'THE COFFEE LOUNGE', title: 'A better kind of coffee break.',
      description: 'Good coffee, something delicious and a place you actually want to spend an hour. Catch up with someone. Catch up with yourself. Let the car be our job.',
      features: ['A comfortable lounge with room to unwind', 'Coffee and light bites, with pre-ordering envisioned', 'A clear view of your day, without the workshop noise'],
      imageAlt: 'A flat white in the sunlit Serv60 concept lounge',
      planTitle: 'Express care. Coffee on your terms.', planCopy: 'Settle into the lounge while your car gets the attention it needs.'
    },
    barber: {
      kicker: 'THE BARBER STUDIO', title: 'A fresh start. For both of you.',
      description: 'Your car gets its care. You get your fresh cut. An on-site barber studio makes a routine appointment feel a little more rewarding.',
      features: ['A considered, comfortable barber studio', 'Appointments coordinated with your car care', 'Subject to barber availability and service duration'],
      imageAlt: 'A leather chair inside the Serv60 concept barber studio',
      planTitle: 'A cared-for car. A fresh cut.', planCopy: 'Coordinate a barber appointment with your express service, subject to availability and timing.'
    },
    work: {
      kicker: 'THE PRIVATE WORKSPACES', title: 'Keep your day moving.',
      description: 'Take the call. Finish the proposal. Find a quiet moment to think. Private work cubicles are part of the vision, so your service appointment can fit around a productive day.',
      features: ['Enclosed cubicles for privacy and focus', 'A dedicated place for your laptop and next idea', 'Bookable alongside your visit, subject to availability'],
      imageAlt: 'An enclosed desk in a Serv60 concept private workspace',
      planTitle: 'Car care outside. Clear thinking inside.', planCopy: 'Use a private workspace to keep your day moving while we look after your vehicle.'
    }
  };
  const experienceDialog = document.querySelector('#experience-dialog');
  const filmDialog = document.querySelector('#film-dialog');
  const film = document.querySelector('#hub-film');
  const planDialog = document.querySelector('#plan-dialog');
  function openDialog(dialog) {
    dialog.showModal();
    body.classList.add('modal-open');
  }
  document.querySelectorAll('[data-experience]').forEach(button => button.addEventListener('click', () => {
    const key = button.dataset.experience;
    const experience = experiences[key];
    const image = document.querySelector('#experience-image');
    image.src = `assets/${key}.webp`;
    image.alt = experience.imageAlt;
    document.querySelector('#experience-kicker').textContent = experience.kicker;
    document.querySelector('#experience-title').textContent = experience.title;
    document.querySelector('#experience-description').textContent = experience.description;
    document.querySelector('#experience-features').replaceChildren(...experience.features.map(text => {
      const item = document.createElement('li');
      item.textContent = text;
      return item;
    }));
    openDialog(experienceDialog);
  }));
  document.querySelector('#watch-film').addEventListener('click', () => {
    if (film.dataset.poster && !film.poster) film.poster = film.dataset.poster;
    openDialog(filmDialog);
    film.play().catch(() => { /* Native controls remain available if autoplay is denied. */ });
  });
  filmDialog.addEventListener('close', () => film.pause());
  document.querySelector('#plan-visit').addEventListener('click', () => openDialog(planDialog));
  document.querySelectorAll('[data-choice]').forEach(button => button.addEventListener('click', () => {
    document.querySelectorAll('[data-choice]').forEach(option => option.setAttribute('aria-pressed', String(option === button)));
    const experience = experiences[button.dataset.choice];
    document.querySelector('#plan-result-title').textContent = experience.planTitle;
    document.querySelector('#plan-result-copy').textContent = experience.planCopy;
  }));
  document.querySelectorAll('dialog').forEach(dialog => {
    dialog.querySelectorAll('[data-close]').forEach(button => button.addEventListener('click', () => dialog.close()));
    dialog.addEventListener('click', event => {
      if (event.target !== dialog) return;
      const rect = dialog.getBoundingClientRect();
      if (event.clientX < rect.left || event.clientX > rect.right || event.clientY < rect.top || event.clientY > rect.bottom) dialog.close();
    });
    dialog.addEventListener('close', () => body.classList.toggle('modal-open', !!document.querySelector('dialog[open]')));
  });
})();
