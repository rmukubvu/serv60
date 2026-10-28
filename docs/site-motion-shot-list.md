# Serv60 site motion: shot list, prompts and encoding

The vision site in `dist/` is built so that pre-rendered video can carry its motion, the way terafab.ai's does. Stage 1 ships with the assets on hand: a layered, parallax hero built from the architectural still, lit and wiped chapters, a grounded car with a floor reflection and a smoothed camera. Stage 2 needs five short generated clips. This document is the brief for those clips and the recipe for getting them onto the site.

## How the site uses video

**Hero loop.** `dist/assets/video/hub-reveal-loop.webm` and `.mp4` play muted, looped and inline behind the hero copy. The hub still is the poster, and the film is only shown once it is actually playing; until then, and wherever it cannot play, the three-plane parallax of the still stands in. It is skipped under reduced motion and when the browser reports Save-Data, and it pauses while the hero is off screen. The files committed today are a placeholder: a seamless 10-second push-in rendered from the still with ffmpeg, so the pipeline is real even though the footage is not yet.

**Chapter clips.** The journey can scrub video with the scroll position. A manifest lists a clip per chapter; the engine fades the active clip in behind the car and sets its playback position to the chapter's local progress, so scrolling backwards plays it backwards. Without a manifest nothing is requested. To enable it, add this before `app.js` in `dist/index.html`:

```html
<script>
window.SERV60_CHAPTER_CLIPS = [
  { stage: 0, webm: 'assets/video/arrival.webm', mp4: 'assets/video/arrival.mp4', poster: 'assets/video/arrival.webp' },
  { stage: 3, webm: 'assets/video/wash.webm', mp4: 'assets/video/wash.mp4', poster: 'assets/video/wash.webp' },
  { stage: 4, webm: 'assets/video/handover.webm', mp4: 'assets/video/handover.mp4', poster: 'assets/video/handover.webp' }
];
</script>
```

Chapters without a clip keep the stage 1 treatment. `npm test` exercises the scrub engine with the hero loop standing in for a chapter clip.

## Shot list

All clips are generated image-to-video from the existing concept stills, so the architecture, the car and the light stay consistent with the page. No audio. No on-screen text or logos. People, if any, out of focus.

| # | Clip | Source still | Where it plays | Length | Camera and light |
|---|---|---|---|---|---|
| 1 | Hub reveal at dusk | `hub-architecture.webp` | Hero loop | 10 s, seamless loop | Slow push-in from the forecourt toward the lounge glass; lounge pendants glowing; a car's tail-lights crossing the right edge of frame; the first and last frames must match for the loop |
| 2 | Arrival | `car.webp` in a bay like `hub-architecture.webp` | Chapter 1, check-in (stage 0) | 6 s | Side view; the champagne car glides from left into a clean express bay and settles; cool dusk from the right, a warm reception light from the left |
| 3 | Wash | `car.webp` | Chapter 4, car wash (stage 3) | 6 s | Water sheeting over the bodywork, fine mist, a sparkle as the sheen catches the light; blue-white top light; wet floor with a mirror reflection |
| 4 | Handover | `car.webp` | Chapter 5, handover (stage 4) | 6 s | Golden-hour light through an open bay door on the right; the car rolls slowly toward the door, reflection on a polished floor |
| 5 | Lounge and work pod | `coffee.webp`, `work.webp` | Experience dialogs, ambient loop | 8 s each, seamless loop | Barely-moving interiors: steam off the flat white, light moving across the pod glass; a tiny dolly, nothing more |

Optional sixth clip for the service chapter (stage 2): the car rising on the two-post lift under amber work lamps, 6 s. Stage 1 already draws the lift, so it can wait.

## Prompts

Use these as the text prompt with the named still as the image input. Keep the still's framing; ask for 24 fps and the length above. Generate at the highest resolution the tool offers, then encode as below.

1. **Hub reveal.** "Cinematic architectural film of this building at dusk. The camera pushes in very slowly from the forecourt toward the glass lounge. Warm pendant lights glow inside; the sky deepens from blue to navy; a car's red tail-lights pass along the right edge. Photoreal, steady, no cuts, seamless loop, no text."
2. **Arrival.** "This champagne-silver sedan glides slowly from the left into a clean, dark express service bay and comes to rest, side-on to camera. Cool dusk light from the right, a warm reception glow from the left, soft reflection on a polished concrete floor. Locked camera, photoreal, no text."
3. **Wash.** "This champagne-silver sedan side-on in a dark wash bay. Sheets of water run over the roof and bodywork, fine mist in the air, a bright sparkle where the sheen catches a blue-white light from above. Wet floor mirrors the car. Locked camera, slow motion feel, photoreal, no text."
4. **Handover.** "This champagne-silver sedan side-on on a polished dark floor. A bay door on the right is open to golden evening light. The car rolls slowly to the right toward the light, its reflection following on the floor. Locked camera, photoreal, no text."
5. **Lounge and work pod.** "Ambient interior loop of this room. Almost nothing moves: steam rises from the coffee, light drifts across the glass, a plant leaf stirs. Tiny slow dolly in. Photoreal, seamless loop, no text." Run once with the coffee still and once with the work pod still.

## Encoding recipe

ffmpeg is not installed on the Mac; run it in Docker. Replace the paths.

Loops (hero, lounge): VP9 and H.264, no audio, web-optimised.

```sh
docker run --rm -v "$PWD":/w lscr.io/linuxserver/ffmpeg:latest -y -i /w/source.mov -an -vf "scale=1920:-2,fps=24,format=yuv420p" -c:v libvpx-vp9 -b:v 0 -crf 33 -row-mt 1 -deadline good -cpu-used 2 /w/hub-reveal-loop.webm
docker run --rm -v "$PWD":/w lscr.io/linuxserver/ffmpeg:latest -y -i /w/source.mov -an -vf "scale=1920:-2,fps=24,format=yuv420p" -c:v libx264 -preset slow -crf 23 -profile:v high -movflags +faststart /w/hub-reveal-loop.mp4
```

Scrub clips (arrival, wash, handover): the same, plus a keyframe on every frame so that seeking is instant. They come out larger, so use a slightly higher CRF.

```sh
docker run --rm -v "$PWD":/w lscr.io/linuxserver/ffmpeg:latest -y -i /w/source.mov -an -vf "scale=1920:-2,fps=24,format=yuv420p" -c:v libvpx-vp9 -b:v 0 -crf 34 -g 1 -row-mt 1 -deadline good -cpu-used 2 /w/arrival.webm
docker run --rm -v "$PWD":/w lscr.io/linuxserver/ffmpeg:latest -y -i /w/source.mov -an -vf "scale=1920:-2,fps=24,format=yuv420p" -c:v libx264 -preset slow -crf 25 -g 1 -keyint_min 1 -profile:v high -movflags +faststart /w/arrival.mp4
docker run --rm -v "$PWD":/w lscr.io/linuxserver/ffmpeg:latest -y -i /w/source.mov -frames:v 1 -vf "scale=1920:-2" -q:v 80 /w/arrival.webp
```

Budget: about 20 MB for the whole page. Target the hero loop at 1 to 2 MB per format, each scrub clip at 3 to 4 MB for the MP4 and less for the WebM, and the lounge loops at 1 MB each. Vercel serves files up to 25 MB each; `verify-site.cjs` checks the simulation stays under that.

## What the founders must generate

Five clips from the prompts above, delivered as the tool's best-quality export (MOV or MP4). Then:

1. Encode with the recipe and drop the files into `dist/assets/video/`, keeping the hero's file names so nothing else changes.
2. Add the manifest script for the chapter clips.
3. Run `npm run preview`, then `npm test` and `npm run screenshots`, and compare the hero and chapters against `qa-output/`.
