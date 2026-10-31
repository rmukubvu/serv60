# Serv60

A cinematic, static one-page introduction to the Serv60 automotive lifestyle hub concept. A film of the hub opens the page, and a film of one visit, played by the scroll through five chapters, explains check-in, inspection, eligible express maintenance, washing and handover. Coffee, a barber studio and private workspaces show how customers could spend their hour. Visitors can join a launch waitlist; waitlist members hear first when Serv60 opens and get early access to the app.

## Deploy on Vercel

Import this repository into Vercel and keep the **Root Directory at the repository root**. The included `vercel.json` selects **Other** as the framework, skips installation and building, and serves the committed `dist` directory. Keep `dist` in Git; it is the website source and deployment output.

Vercel also builds each file in the root `api/` directory as a Node.js function alongside the static output, so the waitlist functions deploy with no change to `vercel.json`. They use only Node built-ins and `fetch`, because the empty install command means no npm packages reach the deployment. The site itself still works without any environment variables; until the store below is configured, the waitlist politely refuses sign-ups instead of pretending to accept them.

`og:url` and `og:image` in `dist/index.html` point at `https://serv60.vercel.app/` and `/assets/og-hub.jpg`, a 1200×630 JPEG of the hub exterior; change both if the site moves to a custom domain.

## Waitlist

The calls to action in the navigation, hero, finale, FAQ, lifestyle panels and visit planner open a waitlist dialog. Each one is a link to `dist/waitlist.html`, so without JavaScript visitors reach the same form on a page of its own; it posts straight to the API and gets a confirmation page back. The form asks for an email address (required), first name, area or suburb in Gauteng, and car make, model and year (all optional), plus a consent checkbox.

| Path | What it does |
| --- | --- |
| `POST /api/waitlist` | Validates the form, rate-limits by IP (8 sign-ups per 10 minutes), stores the entry and, if configured, sends one confirmation email. Answers JSON for the dialog and an HTML page for the no-JavaScript form. |
| `GET /api/waitlist-export` | The whole list as CSV, for the founders. Needs `Authorization: Bearer <WAITLIST_EXPORT_SECRET>`. |
| `GET` / `POST /api/waitlist-unsubscribe?token=…` | The unsubscribe link in every email. Opening it asks for confirmation (mail scanners open links on their own); confirming deletes the entry. Mail apps' one-click unsubscribe posts here too. |

The entry points in `api/` are thin; the logic lives in `server/waitlist/`.

### Store: Upstash Redis

Entries live in **Upstash Redis from the Vercel Marketplace**. Upstash has a free tier and a REST API, so the functions need no database driver. To set it up:

1. In the Vercel project, open **Storage** (or the **Marketplace**), choose **Upstash → Upstash for Redis**, create a database and connect it to the project for **Production**. Connect a separate database to **Preview** so test sign-ups never mix with real ones.
2. Choose the database region deliberately. It decides where the personal data is stored, which matters under POPIA (see below), and it should be close to the region of the Vercel functions.
3. Check **Settings → Environment Variables**. The integration adds the REST URL and token as either `UPSTASH_REDIS_REST_URL` / `UPSTASH_REDIS_REST_TOKEN` or `KV_REST_API_URL` / `KV_REST_API_TOKEN`; both names work.
4. Add `WAITLIST_EXPORT_SECRET` and, if wanted, the email variables below, then redeploy: new variables apply only to new deployments.

| Variable | Needed | Purpose |
| --- | --- | --- |
| `UPSTASH_REDIS_REST_URL` or `KV_REST_API_URL` | Yes | Upstash REST endpoint. Added by the Marketplace integration. |
| `UPSTASH_REDIS_REST_TOKEN` or `KV_REST_API_TOKEN` | Yes | Upstash REST token. Added by the Marketplace integration. Never use the read-only token. |
| `WAITLIST_EXPORT_SECRET` | For the export | A random value of at least 24 characters, for example from `openssl rand -base64 32`. |
| `RESEND_API_KEY` | Optional | Turns on the confirmation email through [Resend](https://resend.com). |
| `WAITLIST_FROM_EMAIL` | With Resend | Sender, such as `Serv60 <hello@your-domain>`, on a domain verified in Resend. Without it no email is sent. |
| `WAITLIST_REPLY_TO` | Optional | Where replies go. People are told to reply to see or correct their details, so this inbox should be read. |
| `WAITLIST_SITE_URL` | Optional | Canonical address for unsubscribe links, such as `https://serv60.co.za`. Defaults to the address the request arrived on. |

Smoke-test a deployment (use a Preview deployment with its own database):

```sh
curl -sS -X POST https://<deployment>/api/waitlist -H 'Content-Type: application/json' -d '{"email":"you@example.com","consent":true,"source":"page"}'
```

`{"ok":true,…}` means the entry was stored. Without a configured store the answer is `503` with `"The waitlist isn’t open just yet."`.

### Exporting the list

```sh
curl -fsS -H "Authorization: Bearer $WAITLIST_EXPORT_SECRET" https://<domain>/api/waitlist-export -o serv60-waitlist.csv
```

The CSV has one row per person, oldest first: dates, email, the optional details, which button they used (`source`), the consent version and time, and a personal `unsubscribe_url`. Put that link in every launch email; whatever tool sends those emails also needs its own unsubscribe handling. Cells that a spreadsheet could run as formulas are prefixed with `'`. The Upstash console's Data Browser shows the same data (keys under `serv60:waitlist:`).

### Data handling

- **Consent.** Each entry records `consentVersion` and `consentedAt`. The wording is `CONSENT_TEXT` in `server/waitlist/input.js`, and `verify-site.cjs` checks that both pages show it exactly. When the wording changes, bump `CONSENT_VERSION`.
- **Minimal data.** Only the form fields, the button used and the consent record are stored. Rate limiting keys on an HMAC of the IP address that expires after 10 minutes; raw IP addresses are never stored. Function logs never include email addresses.
- **Repeat sign-ups** get the same answer as first ones, so the form cannot reveal who is on the list. They refresh the consent record and any details given, never blank earlier answers, and send no second email.
- **Unsubscribe deletes.** Confirming the unsubscribe link removes the entry, its index record and its token.
- **Privacy notice.** `dist/waitlist.html#privacy` explains what is collected, why, who can see it, how long it is kept and how to complain to the Information Regulator. Have it reviewed before launch, and add a contact address for privacy requests if the confirmation email is not enabled, since people otherwise have no email to reply to until launch.
- **Where data lives.** Vercel, Upstash and Resend may process the data outside South Africa. The notice says so; make sure each provider's data processing terms are accepted for the account.
- **After launch,** export the list, send the launch email and delete the database or the entries once the waitlist has served its purpose, as the notice promises.

## Preview locally

With Python 3 installed, run from the repository root:

```sh
npm run preview
```

Open `http://127.0.0.1:4173`. No package installation is required to preview or deploy the site. `dist/index.html` can also be opened directly for an offline presentation.

The Python preview serves only static files, so sending the waitlist form there shows the honest "isn’t available" message. The browser checks mock the API instead; to try the real functions, use a Vercel preview deployment with its own Upstash database.

## Edit the website

- `dist/index.html`: content, navigation, dialogs and accessible structure.
- `dist/styles.css`: responsive layout, the hero and journey films and the hospitality imagery.
- `dist/app.js`: the journey film and its chapters, stage navigation, the hero film and local interactions.
- `dist/waitlist.html` and `dist/waitlist.js`: the no-JavaScript waitlist page, and the form behaviour (inline validation, sending, confirmation) shared by that page and the dialog. When `styles.css`, `app.js` or `waitlist.js` changes, update its `?v=` query in both HTML files to the first 12 hex characters of the file's SHA-256.
- `api/` and `server/waitlist/`: the waitlist functions.
- `dist/assets/`: optimized WebP images: the six renders of the hub at 1168 and 800 px (`coffee`, `barber`, `work`, `workshop`, `reception`, `hub-exterior`), the film's opening frame as the hero poster (`hub-film-poster`, 1280 and 960 px), the finale still, three film-strip frames in `film/` and the simulation poster; plus the favicon, the Open Graph card `og-hub.jpg` and the complete operations simulation.
- `dist/assets/video/`: the hero film in WebM and MP4 at 1280 px (`hub-film.*`) and 720 px (`hub-film-720.*`), and the journey film as five shots, `journey-check-in`, `-inspection`, `-service`, `-wash` and `-handover`, each an MP4 at 1280 px and 720 px (`-720`) with its opening frame as a WebP still.

Reduced motion and pages without JavaScript replace the pinned journey with ordinary reading flow, each chapter with its still from the film. There are no trackers, cookies, remote fonts or booking submissions; the waitlist form is the only customer data the site collects.

## Motion

The page is built to feel like one camera move rather than a slideshow.

- **Hero.** As on terafab.ai, the film leads. A 30-second generated film of the hub, `dist/assets/video/hub-film.webm` and `.mp4` (phones get `hub-film-720.*`), plays full-bleed, muted and looped over its own opening frame, `hub-film-poster.webp`, which stands in wherever the film cannot play. A soft fade from the left carries a short headline, one line of copy and two compact buttons, and leaves the rest of the frame open. The film is fetched only after the page has painted, skipped under reduced motion, Save-Data and connections Chrome measures as 2G, and paused while the hero is off screen or a dialog is open; a toggle in the hero's bottom bar turns its sound on. The copy wipes in line by line, and as the page scrolls the scene recedes and the copy parts, as CSS scroll-driven animations on the compositor, so it never lags the scroll; browsers without them show the scene still.
- **Journey.** A 25-second generated film follows one car through the visit: arrival, inspection with the bonnet up, service on the lift, the wash and the handover. It is cut at its own shot changes into one clip per chapter, and the scroll position is its clock: each chapter's shot plays across that chapter's scroll and plays backwards when the page goes back up. The film follows the scroll with a short lag so wheel steps become one continuous move, and changes shot exactly when the copy does; the shots either side wait on the frame they meet it at, so every cut is clean in both directions. The chapter, count, rail and progress bar follow the scroll position exactly, and the copy wipes out and in with the direction of travel. On desktops and landscape screens the film fills the stage behind the copy; on portrait tablets and phones it is a band beneath it. The shots load only as the journey comes near, phones get the 720-pixel cut, and on Save-Data or a 2G connection only their stills load, changing with the chapters. [docs/site-motion-shot-list.md](docs/site-motion-shot-list.md) has the cut points and the encoding recipe.
- **Everywhere else.** Headings wipe in, the renders settle from a slight zoom and drift inside their frames at a different speed from their captions, three frames from the film sit under the manifesto, and the finale carries the film's closing aerial.

Reduced motion replaces all of this with the ordinary reading flow, one still in the hero and a still in each chapter. Everything remains readable without JavaScript.

## Quality checks

For browser checks, use Node.js 20 or newer, install the pinned development dependency and its Chromium browser:

```sh
npm ci
npx playwright install chromium
npm run check
```

Keep `npm run preview` running in another terminal, then run:

```sh
npm test
npm run screenshots
```

`npm test` first runs `verify-api.mjs`, which drives the waitlist functions against an in-memory Upstash and Resend (`npm run test:api` runs it alone, with no preview or browser). Tests cover forward and reverse scrolling, response within two animation frames, the journey film showing and scrubbing the right shot in the right cut for the screen, its lazy loading and Save-Data stills, idle style writes, image proportions, desktop/mobile/landscape layouts, dialogs, focus restoration, video playback, reduced motion, no-JavaScript reading and missing assets. For the waitlist they cover every call to action, inline validation, unavailable, rejected and offline submissions, the success state, small phones and the no-JavaScript form, plus validation, storage, repeat sign-ups, rate limiting, confirmation email, CSV export and unsubscribe on the server side. Reports and screenshots go to the ignored `qa-output` directory. `SERV60_PREVIEW_URL` overrides the preview URL; `SERV60_BROWSER_CHANNEL=chrome` uses installed Google Chrome instead of bundled Chromium. The Python preview answers no range requests, so the journey film cannot seek there and its scrub checks skip; `npx serve dist` answers them, but rewrites `/waitlist.html` to `/waitlist` unless it is started with `-c` and a `serve.json` containing `{"cleanUrls": false, "rewrites": [{"source": "/", "destination": "/index.html"}]}` (the rewrite keeps `/` on `index.html` once the defaults are overridden).

## Simulation and visual assets

The complete operations simulation is available in the player and as an MP4 download: `dist/assets/auto-hub-operations-simulation.mp4`. It retains the full **96-second duration, 1920×1080 resolution and 24 fps**, with no audio. The H.264 web copy is approximately **14.86 MB** and loads only when requested. The separately maintained investor pitch is not bundled into this website.

The architectural hub, coffee lounge, barber studio and workspace are generated concept images, and both films are generated video. The optimized deliverable files are committed; generation utilities and machine-specific paths are unnecessary for deployment. [IMAGE-PROMPTS.md](IMAGE-PROMPTS.md) records the architectural concept prompt and asset preparation. These visuals illustrate intended spaces, not an operating location.

## Concept and service scope

Serv60 is a concept in development. **60 minutes is a target for the entire eligible express visit**, including check-in, inspection, maintenance, quality checks, wash and handover. Repairs and unexpected work require separate scope and timing. Opening dates, booking availability, equipment and the operating target remain subject to launch planning and validation. The visit chooser illustrates the experience; it does not place a booking. The waitlist promises launch news and early access to the app only: no opening date, and no money-back guarantee until it is offered after a measured soft launch.

## Platform architecture

[Serv60 platform HLD](docs/HLD-serv60-platform.md) proposes the customer mobile app, operations calendar, booking and parts planning, direct-pay roadside assistance and optional ride home. It uses DDD with Java 25 LTS, Spring Boot 4.1.1 and separately built modules, and includes AWS deployment, extraction boundaries, cost assumptions and a path from one South African hub to ten. [Ubiquitous language](UBIQUITOUS_LANGUAGE.md) defines the shared business terms. These are design documents; the current website remains a static concept presentation.

[Mobile app specification reconciliation](docs/mobile-app-spec-reconciliation.md) checks an externally drafted mobile specification against this platform: it restates the stack that is built, maps each proposed module to what exists, and lists the open product decisions.

[Parts Desk HLD](docs/HLD-parts-desk.md) designs a separate purchasing assistant for workshops and approved parts suppliers. It covers the request-to-order data flow, Java/Go/Rust decision, private Nostr messaging, agent permissions, AWS Cape Town hosting, delivery phases and cost estimates. The accompanying [cost model](docs/parts-desk-cost-model.json) preserves the workload assumptions, rates and source references. This is a proposed product architecture, not a deployed integration.
