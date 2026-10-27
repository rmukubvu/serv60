(() => {
  'use strict';
  const body = document.body;
  const reducedMotion = window.matchMedia('(prefers-reduced-motion: reduce)');
  const journey = document.querySelector('#journey');
  const stage = document.querySelector('#journey-stage');
  const scene = document.querySelector('#car-scene');
  const chapters = [...document.querySelectorAll('[data-chapter]')];
  const stageButtons = [...document.querySelectorAll('[data-go]')];
  const progressBar = document.querySelector('#journey-progress');
  const chapterCount = document.querySelector('#chapter-count');
  const car = document.querySelector('#car-object');
  const shadow = scene.querySelector('.car-shadow');
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
  const animatedElements = [car, shadow, ticket, scan, liftTop, progressBar, ...Object.values(effects).flat()];
  const writtenStyles = new WeakMap();
  const clamp = (value, min = 0, max = 1) => Math.min(max, Math.max(min, value));
  const mix = (a, b, amount) => a + (b - a) * amount;
  const ease = value => value * value * (3 - 2 * value);
  let activeChapter = -1;
  let frame = 0;
  let currentProgress = -1;
  let targetProgress = 0;
  let journeyTop = 0;
  let journeyTravel = 1;
  let carHeight = 0;
  let sceneHeight = 0;

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
  function setChapter(index) {
    if (activeChapter === index) return;
    activeChapter = index;
    stage.dataset.stage = String(index);
    chapterCount.textContent = `0${index + 1} / 05`;
    chapters.forEach((chapter, i) => {
      chapter.classList.toggle('is-active', i === index);
      if (reducedMotion.matches) chapter.removeAttribute('aria-hidden');
      else chapter.setAttribute('aria-hidden', String(i !== index));
    });
    stageButtons.forEach((button, i) => {
      if (i === index) button.setAttribute('aria-current', 'step');
      else button.removeAttribute('aria-current');
    });
  }
  function render(progress) {
    const [x, y, scale, rotation] = interpolate(progress);
    setStyle(car, 'transform', `translate3d(${x.toFixed(3)}%,${y.toFixed(3)}%,0) scale(${scale.toFixed(4)}) rotate(${rotation.toFixed(3)}deg)`);
    setStyle(shadow, 'transform', `translate3d(${(x / .8).toFixed(3)}%,0,0) scale(${scale.toFixed(4)})`);
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
    setStyle(progressBar, 'transform', `scaleX(${Math.max(.015, progress).toFixed(4)})`);
    setChapter(Math.min(4, Math.floor(progress * 5)));
    currentProgress = progress;
  }
  function tick() {
    frame = 0;
    if (targetProgress !== currentProgress) render(targetProgress);
  }
  function onScroll() {
    if (reducedMotion.matches) return;
    targetProgress = clamp((window.scrollY - journeyTop) / journeyTravel);
    if (!frame && targetProgress !== currentProgress) frame = requestAnimationFrame(tick);
  }
  function measure() {
    // Geometry reads happen only on layout changes, never in the scroll renderer.
    journeyTop = window.scrollY + journey.getBoundingClientRect().top;
    journeyTravel = Math.max(1, journey.offsetHeight - stage.offsetHeight);
    carHeight = car.offsetHeight;
    sceneHeight = scene.offsetHeight;
    const rainTravel = rain.offsetHeight + 72;
    rain.style.setProperty('--rain-travel', `${rainTravel}px`);
    currentProgress = -1;
    onScroll();
  }
  function configureMotion() {
    if (frame) cancelAnimationFrame(frame);
    frame = 0;
    body.classList.toggle('motion', !reducedMotion.matches);
    chapters.forEach(chapter => chapter.removeAttribute('aria-hidden'));
    activeChapter = -1;
    animatedElements.forEach(element => {
      element.removeAttribute('style');
      writtenStyles.delete(element);
    });
    currentProgress = -1;
    if (reducedMotion.matches) {
      stage.dataset.stage = '0';
      progressBar.style.transform = 'scaleX(1)';
      stageButtons.forEach(button => button.removeAttribute('aria-current'));
      chapterCount.textContent = '01 — 05';
    } else {
      measure();
      render(targetProgress);
    }
  }

  body.classList.add('js');
  document.querySelectorAll('[data-experience], [data-go], #watch-film, #plan-visit')
    .forEach(button => { button.disabled = false; });
  configureMotion();
  reducedMotion.addEventListener('change', configureMotion);
  window.addEventListener('scroll', onScroll, { passive: true });
  window.addEventListener('resize', measure, { passive: true });
  window.addEventListener('load', measure, { once: true });
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
    dialog.addEventListener('close', () => body.classList.toggle('modal-open', !!document.querySelector('dialog[open]')));
  });
})();
