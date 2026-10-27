(() => {
  'use strict';
  // Mirrors server/waitlist/input.js, so visitors see the same messages before and after sending.
  const latestYear = new Date().getFullYear() + 1;
  const tooLong = limit => value => value.length > limit ? `Keep this to ${limit} characters or fewer.` : '';
  const rules = {
    email: value => !value ? 'Enter your email address.'
      : value.length > 254 || !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(value) ? 'Enter an email address like name@example.com.' : '',
    firstName: tooLong(60),
    area: tooLong(80),
    vehicleMake: tooLong(40),
    vehicleModel: tooLong(60),
    vehicleYear: value => !value || (/^\d{4}$/.test(value) && value >= 1950 && value <= latestYear) ? '' : `Enter a year between 1950 and ${latestYear}.`,
    consent: value => value ? '' : 'Tick the box so we can email you about the launch.'
  };
  const unavailable = 'The waitlist isn’t available right now. Please try again later.';

  // Without JavaScript the same form posts to the API and gets a page back.
  document.querySelectorAll('[data-waitlist-form]').forEach(form => {
    const flow = form.closest('[data-waitlist-flow]');
    const success = flow.querySelector('[data-waitlist-success]');
    const status = form.querySelector('[data-form-status]');
    const submit = form.querySelector('[type="submit"]');
    const submitLabel = submit.firstChild.textContent;
    const inputs = Object.keys(rules).map(name => form.elements[name]);
    let attempted = false;
    form.noValidate = true;

    const valueOf = input => input.type === 'checkbox' ? input.checked : input.value.trim();
    function showError(input, message) {
      form.querySelector(`[data-error-for="${input.name}"]`).textContent = message;
      if (message) input.setAttribute('aria-invalid', 'true');
      else input.removeAttribute('aria-invalid');
    }
    const validate = input => {
      const message = rules[input.name](valueOf(input));
      showError(input, message);
      return message;
    };
    function setBusy(busy) {
      form.setAttribute('aria-busy', String(busy));
      // aria-disabled keeps focus on the button, where a disabled one would drop it.
      submit.setAttribute('aria-disabled', String(busy));
      submit.firstChild.textContent = busy ? 'Joining… ' : submitLabel;
    }
    function showSuccess(email, confirmationSent) {
      success.querySelector('[data-success-email]').textContent = email;
      success.querySelector('[data-success-confirmation]').hidden = !confirmationSent;
      flow.querySelector('[data-waitlist-panel]').hidden = true;
      success.hidden = false;
      success.querySelector('[data-success-heading]').focus();
    }

    inputs.forEach(input => {
      // Judge a field once someone has finished with it, then clear the message as soon as it is fixed.
      input.addEventListener('blur', () => { if (attempted || (input.type !== 'checkbox' && valueOf(input))) validate(input); });
      input.addEventListener(input.type === 'checkbox' ? 'change' : 'input', () => {
        if (input.getAttribute('aria-invalid') === 'true') validate(input);
      });
    });

    form.addEventListener('submit', async event => {
      event.preventDefault();
      if (form.getAttribute('aria-busy') === 'true') return;
      attempted = true;
      status.textContent = '';
      const invalid = inputs.filter(validate);
      if (invalid.length) {
        invalid[0].focus();
        return;
      }
      const data = Object.fromEntries(inputs.map(input => [input.name, valueOf(input)]));
      data.source = form.elements.source.value;
      setBusy(true);
      try {
        const response = await fetch(form.action, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
          body: JSON.stringify(data),
          signal: AbortSignal.timeout ? AbortSignal.timeout(15000) : undefined
        });
        const result = await response.json().catch(() => ({}));
        if (response.ok && result.ok) {
          showSuccess(data.email, result.confirmationSent === true);
          return;
        }
        const rejected = inputs.filter(input => result.fields?.[input.name]);
        rejected.forEach(input => showError(input, result.fields[input.name]));
        if (rejected.length) rejected[0].focus();
        else status.textContent = result.message || unavailable;
      } catch {
        status.textContent = 'We couldn’t reach the waitlist. Check your connection and try again.';
      } finally {
        setBusy(false);
      }
    });
  });
})();
