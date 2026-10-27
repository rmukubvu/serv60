/** Server-rendered pages for visitors without JavaScript and for unsubscribe links. */

export function escapeHtml(value) {
  return String(value).replace(/[&<>"']/g, character => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[character]);
}

/** `heading` and `body` are trusted markup; escape anything a visitor sent before passing it in. */
export function htmlPage({ status = 200, title, eyebrow, heading, body, headers = {} }) {
  const html = `<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <meta name="theme-color" content="#111718">
  <meta name="robots" content="noindex">
  <title>${escapeHtml(title)} — Serv60</title>
  <link rel="icon" href="/assets/favicon.svg" type="image/svg+xml">
  <link rel="stylesheet" href="/styles.css">
</head>
<body class="page">
  <a class="skip-link" href="#main">Skip to content</a>
  <header class="page-header"><div class="nav-inner">
    <a href="/" class="wordmark" aria-label="Serv60 home">serv<span>60</span><i aria-hidden="true"></i></a>
    <a class="nav-cta" href="/">Back to the vision <span aria-hidden="true">↗</span></a>
  </div></header>
  <main id="main">
    <section class="page-intro page-message section-wrap" aria-labelledby="page-title">
      <p class="eyebrow">${escapeHtml(eyebrow)}</p>
      <h1 id="page-title">${heading}</h1>
      <div class="page-copy">${body}</div>
    </section>
  </main>
  <footer class="site-footer section-wrap"><div class="footer-bottom"><span>© 2026 Serv60. A vision for a better service visit.</span><p>Serv60 is a concept in development. Opening dates and services remain subject to launch planning.</p></div></footer>
</body>
</html>`;
  return new Response(html, {
    status,
    headers: { 'Content-Type': 'text/html; charset=utf-8', 'Cache-Control': 'no-store', ...headers }
  });
}
