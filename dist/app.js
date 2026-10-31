(() => {
  'use strict';
  const body = document.body;
  const reducedMotion = window.matchMedia('(prefers-reduced-motion: reduce)');
  const hero = document.querySelector('#top');
  const heroVideo = hero.querySelector('#hero-video');
  const soundToggle = document.querySelector('#hero-sound');
  const journey = document.querySelector('#journey');
  const stage = document.querySelector('#journey-stage');
  const filmFrame = document.querySelector('#journey-film');
  const chapters = [...document.querySelectorAll('[data-chapter]')];
  const stageButtons = [...document.querySelectorAll('[data-go]')];
  const progressBar = document.querySelector('#journey-progress');
  const railMarker = document.querySelector('#rail-marker');
  const chapterCount = document.querySelector('#chapter-count');
  // One shot of the film per chapter. `requested` is the last seek asked of it.
  const shots = [...filmFrame.querySelectorAll('video')].map(video => ({ video, requested: -1 }));
  const animatedElements = [progressBar, railMarker, ...shots.map(shot => shot.video)].filter(Boolean);
  const writtenStyles = new WeakMap();
  const clamp = (value, min = 0, max = 1) => Math.min(max, Math.max(min, value));
  let activeChapter = -1;
  let frame = 0;
  let lastFrameTime = 0;
  let rawProgress = 0;         // where the page actually is: drives chapters, the rail and the bar
  let cameraProgress = -1;     // where the film is: follows rawProgress with a short lag
  let renderedRaw = -1;
  let journeyTop = 0;
  let journeyTravel = 1;
  let railTravel = 0;
  const leaveTimers = new Map();
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
  }

  // A small dead zone at each boundary, so a hand resting on the trackpad cannot flip a chapter back and forth.
  function chapterFor(progress) {
    const position = progress * 5;
    const natural = Math.min(4, Math.floor(position));
    if (activeChapter < 0 || natural === activeChapter) return natural;
    const margin = .06;
    if (natural > activeChapter) return position >= activeChapter + 1 + margin ? natural : activeChapter;
    return position < activeChapter - margin ? natural : activeChapter;
  }
  // Deterministic parts follow the scroll position exactly: chapter, count, rail and bar.
  function renderTimeline(progress) {
    setStyle(progressBar, 'transform', `scaleX(${Math.max(.015, progress).toFixed(4)})`);
    setStyle(railMarker, 'transform', `translate3d(0,${(progress * railTravel).toFixed(2)}px,0)`);
    setChapter(chapterFor(progress));
    renderedRaw = progress;
  }

  // The film follows with a short lag, so wheel steps become one continuous move. It shows the shot of
  // the chapter on screen; the shots either side wait on their nearest frame, so a change is a clean cut both ways.
  function renderFilm(progress) {
    const index = activeChapter < 0 ? Math.min(4, Math.floor(progress * 5)) : activeChapter;
    const local = clamp(progress * 5 - index);
    shots.forEach((shot, i) => {
      setOpacity([shot.video], i === index ? 1 : 0);
      const duration = shot.video.duration;
      if (!duration) return;
      const time = i === index ? local * duration : i < index ? Math.max(0, duration - .05) : 0;
      // Track what was asked for, not what the decoder reports: a new seek simply replaces a pending one.
      if (Math.abs(shot.requested - time) > 1 / 48) {
        shot.requested = time;
        shot.video.currentTime = time;
      }
    });
    cameraProgress = progress;
  }

  // The hero planes, the hero copy and the photographs drift with CSS scroll-driven animations in
  // styles.css: they run on the compositor, in step with the scroll, which JavaScript cannot promise.

  function tick(now) {
    const elapsed = lastFrameTime ? Math.min(64, now - lastFrameTime) : 16;
    lastFrameTime = now;
    frame = 0;
    if (rawProgress !== renderedRaw) renderTimeline(rawProgress);
    if (cameraProgress !== rawProgress) {
      const lag = 1 - Math.exp(-elapsed / 85);
      let next = cameraProgress < 0 ? rawProgress : cameraProgress + (rawProgress - cameraProgress) * lag;
      if (Math.abs(rawProgress - next) < .0004) next = rawProgress;
      renderFilm(next);
    }
    if (cameraProgress !== rawProgress) frame = requestAnimationFrame(tick);
    else lastFrameTime = 0;
  }
  function schedule() {
    if (!frame) frame = requestAnimationFrame(tick);
  }
  function onScroll() {
    if (reducedMotion.matches) return;
    rawProgress = clamp((window.scrollY - journeyTop) / journeyTravel);
    if (rawProgress !== renderedRaw || cameraProgress !== rawProgress) schedule();
  }
  function measure() {
    // Geometry reads happen only on layout changes, never in the scroll renderer.
    journeyTop = window.scrollY + journey.getBoundingClientRect().top;
    journeyTravel = Math.max(1, journey.offsetHeight - stage.offsetHeight);
    const rail = railMarker ? railMarker.parentElement : null;
    railTravel = rail ? Math.max(0, rail.offsetHeight - railMarker.offsetHeight) : 0;
    renderedRaw = -1;
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
    activeChapter = -1;
    animatedElements.forEach(element => {
      element.removeAttribute('style');
      writtenStyles.delete(element);
    });
    renderedRaw = -1;
    cameraProgress = -1;
    configureHeroVideo();
    if (journeyNear) loadFilm();
    if (reducedMotion.matches) {
      stage.dataset.stage = '0';
      stage.dataset.direction = 'forward';
      progressBar.style.transform = 'scaleX(1)';
      stageButtons.forEach(button => button.removeAttribute('aria-current'));
      chapterCount.textContent = '01 — 05';
    } else {
      measure();
      renderTimeline(rawProgress);
      renderFilm(rawProgress);
    }
  }

  // Save-Data, or a link Chrome measures as 2G. Its "3g" also covers ordinary connections with a long
  // round trip, as from South Africa to most servers, so that alone is no reason to hold the films back.
  function constrainedNetwork() {
    const connection = navigator.connection || {};
    return !!connection.saveData || /(^|-)2g$/.test(connection.effectiveType || '');
  }

  // The hero film: a muted loop over the stills, shown only once it is actually playing.
  // It is fetched only after the page has painted, so it never competes with the hero still.
  let heroVideoReady = false;
  let heroFilmStarted = false;
  let heroVisible = true;
  function startHeroFilm() {
    const video = heroVideo;
    if (heroFilmStarted || reducedMotion.matches) return;
    heroFilmStarted = true;
    // Phones get the 720-pixel encodes: the same film at a third of the bytes.
    if (window.matchMedia('(max-width: 900px)').matches) {
      video.querySelectorAll('source').forEach(source => {
        const small = video.dataset[source.type === 'video/webm' ? 'mobileWebm' : 'mobileMp4'];
        if (small) source.src = small;
      });
    }
    video.preload = 'auto';
    video.load();
    video.play().catch(() => { /* The opening frame stays in place when autoplay is refused. */ });
  }
  // The film's own sound stays off until asked for; the tap on the toggle is the gesture browsers need before they allow it.
  function setSound(on) {
    heroVideo.muted = !on;
    soundToggle.setAttribute('aria-pressed', String(on));
    soundToggle.querySelector('span').textContent = on ? 'Sound on' : 'Sound off';
    if (on && heroFilmStarted) heroVideo.play().catch(() => {});
  }
  function configureHeroVideo() {
    const video = heroVideo;
    if (!video) return;
    if (reducedMotion.matches || constrainedNetwork()) {
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
        heroVisible = entries[0].isIntersecting;
        if (reducedMotion.matches || !heroFilmStarted) return;
        if (heroVisible && !document.querySelector('dialog[open]')) video.play().catch(() => {});
        else video.pause();
      }).observe(hero);
      soundToggle.addEventListener('click', () => setSound(video.muted));
      const start = () => setTimeout(startHeroFilm, 700);
      if (document.readyState === 'complete') start();
      else window.addEventListener('load', start, { once: true });
      return;
    }
    if (heroFilmStarted) video.play().catch(() => {});
    else if (document.readyState === 'complete') startHeroFilm();
  }

  // The journey film loads only as the journey comes near, and phones get the 720-pixel cut. Each shot's
  // opening frame stands in until it can play, and on Save-Data or a 2G connection it is all that loads.
  let journeyNear = false;
  let filmLoaded = false;
  function loadFilm() {
    if (filmLoaded || reducedMotion.matches) return;
    filmLoaded = true;
    const small = window.matchMedia('(max-width: 900px)').matches;
    const constrained = constrainedNetwork();
    shots.forEach(shot => {
      const video = shot.video;
      video.poster = video.dataset.poster;
      if (constrained) return;
      const position = () => { shot.requested = -1; if (cameraProgress >= 0) renderFilm(cameraProgress); };
      // The duration is known from here, so the shot can be put in place.
      video.addEventListener('loadedmetadata', position);
      // A seek asked for before the data arrived can be dropped; ask again once it can play, unless it landed.
      // Every finished seek also fires canplay, so asking unconditionally would seek forever.
      video.addEventListener('canplay', () => { if (Math.abs(video.currentTime - shot.requested) > 1 / 48) position(); });
      video.preload = 'auto';
      video.src = small ? video.dataset.mobileSrc : video.dataset.src;
    });
  }

  body.classList.add('js');
  document.querySelectorAll('[data-experience], [data-go], #watch-film, #plan-visit')
    .forEach(button => { button.disabled = false; });
  configureMotion();
  reducedMotion.addEventListener('change', configureMotion);
  window.addEventListener('scroll', onScroll, { passive: true });
  window.addEventListener('resize', measure, { passive: true });
  window.addEventListener('load', measure, { once: true });
  // Once the opening reveals have had their time, pin the hero copy and header in place.
  window.addEventListener('load', () => setTimeout(() => body.classList.add('is-settled'), 3200), { once: true });
  window.addEventListener('pageshow', measure);
  new ResizeObserver(measure).observe(document.querySelector('.manifesto'));
  new IntersectionObserver(entries => {
    journeyNear = entries[0].isIntersecting;
    if (journeyNear) loadFilm();
  }, { rootMargin: '60% 0px' }).observe(journey);

  stageButtons.forEach(button => button.addEventListener('click', () => {
    const index = Number(button.dataset.go);
    if (reducedMotion.matches) chapters[index].scrollIntoView({ block: 'center' });
    else {
      measure();
      window.scrollTo({ top: journeyTop + journeyTravel * ((index + .38) / 5), behavior: 'smooth' });
    }
  }));
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
    // A film with its sound on should not keep playing under a dialog.
    if (!heroVideo.muted) heroVideo.pause();
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
  // Waitlist calls to action are links to waitlist.html; with JavaScript they open the dialog instead.
  const waitlistDialog = document.querySelector('#waitlist-dialog');
  document.querySelectorAll('[data-waitlist]').forEach(trigger => trigger.addEventListener('click', event => {
    event.preventDefault();
    // Close the dialog the button sits in first, so focus later returns to what opened that one.
    trigger.closest('dialog')?.close();
    waitlistDialog.querySelector('[name="source"]').value = trigger.dataset.waitlist;
    openDialog(waitlistDialog);
  }));
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
    dialog.addEventListener('close', () => {
      const stillOpen = !!document.querySelector('dialog[open]');
      body.classList.toggle('modal-open', stillOpen);
      if (!stillOpen && heroFilmStarted && heroVisible && heroVideo.paused && !reducedMotion.matches) heroVideo.play().catch(() => {});
    });
  });
})();
