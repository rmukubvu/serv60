# Serv60

A cinematic, static one-page introduction to the Serv60 automotive lifestyle hub concept. A destination-led opening, one persistent car and a five-stage scroll journey explain check-in, inspection, eligible express maintenance, washing and handover. Coffee, a barber studio and private workspaces show how customers could spend their hour.

## Deploy on Vercel

Import this repository into Vercel and keep the **Root Directory at the repository root**. The included `vercel.json` selects **Other** as the framework, skips installation and building, and serves the committed `dist` directory. No API keys, backend or environment variables are needed. Keep `dist` in Git; it is the website source and deployment output.

Once the Vercel or custom domain is known, add its absolute URL as `og:url` and the absolute URL of `/assets/hub-architecture.webp` as `og:image` in `dist/index.html`. Both are deliberately omitted until that domain is confirmed; title, description and Open Graph type remain configured.

## Preview locally

With Python 3 installed, run from the repository root:

```sh
npm run preview
```

Open `http://127.0.0.1:4173`. No package installation is required to preview or deploy the site. `dist/index.html` can also be opened directly for an offline presentation.

## Edit the website

- `dist/index.html`: content, navigation, dialogs and accessible structure.
- `dist/styles.css`: responsive layout, hospitality imagery and car scene.
- `dist/app.js`: scroll interpolation, stage navigation and local interactions.
- `dist/assets/`: optimized WebP images, poster, favicon and complete operations simulation.

The animation preserves the car image's native 1774×887 proportions with uniform scaling. Its position follows scrolling without catch-up easing. Wash particles pause outside the visible wash stage. Reduced motion replaces the pinned scene with ordinary reading flow; every chapter remains readable without JavaScript. There are no trackers, remote fonts, booking submissions or customer-data collection.

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

Tests cover forward and reverse scrolling, response within two animation frames, image proportions, idle style writes, particle pauses, desktop/mobile/landscape layouts, dialogs, focus restoration, video playback, reduced motion, no-JavaScript reading and missing assets. Reports and screenshots go to the ignored `qa-output` directory. `SERV60_PREVIEW_URL` overrides the preview URL; `SERV60_BROWSER_CHANNEL=chrome` uses installed Google Chrome instead of bundled Chromium.

## Simulation and visual assets

The complete operations simulation is available in the player and as an MP4 download: `dist/assets/auto-hub-operations-simulation.mp4`. It retains the full **96-second duration, 1920×1080 resolution and 24 fps**, with no audio. The H.264 web copy is approximately **14.86 MB** and loads only when requested. The separately maintained investor pitch is not bundled into this website.

The architectural hub, car, coffee lounge, barber studio and workspace are generated concept images. The optimized deliverable files are committed; generation utilities and machine-specific paths are unnecessary for deployment. [IMAGE-PROMPTS.md](IMAGE-PROMPTS.md) records the architectural concept prompt and asset preparation. These visuals illustrate intended spaces, not an operating location.

## Concept and service scope

Serv60 is a concept in development. **60 minutes is a target for the entire eligible express visit**, including check-in, inspection, maintenance, quality checks, wash and handover. Repairs and unexpected work require separate scope and timing. Opening dates, booking availability, equipment and the operating target remain subject to launch planning and validation. The visit chooser illustrates the experience; it does not place a booking.

## Platform architecture

[Serv60 platform HLD](docs/HLD-serv60-platform.md) proposes the customer mobile app, operations calendar, booking and parts planning, direct-pay roadside assistance and optional ride home. It uses DDD with Java 25 LTS, Spring Boot 4.1.1 and separately built modules, and includes AWS deployment, extraction boundaries, cost assumptions and a path from one South African hub to ten. [Ubiquitous language](UBIQUITOUS_LANGUAGE.md) defines the shared business terms. These are design documents; the current website remains a static concept presentation.

[Mobile app specification reconciliation](docs/mobile-app-spec-reconciliation.md) checks an externally drafted mobile specification against this platform: it restates the stack that is built, maps each proposed module to what exists, and lists the open product decisions.

[Parts Desk HLD](docs/HLD-parts-desk.md) designs a separate purchasing assistant for workshops and approved parts suppliers. It covers the request-to-order data flow, Java/Go/Rust decision, private Nostr messaging, agent permissions, AWS Cape Town hosting, delivery phases and cost estimates. The accompanying [cost model](docs/parts-desk-cost-model.json) preserves the workload assumptions, rates and source references. This is a proposed product architecture, not a deployed integration.
