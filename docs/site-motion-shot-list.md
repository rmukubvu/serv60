# Serv60 site motion: the films, how they are cut and how to replace them

The vision site in `dist/` lets pre-rendered video carry its motion, the way terafab.ai's does. Two generated films do the work: a 30-second film of the hub opens the page, and a 25-second film of one car's visit plays through "02 / Follow your car" as the visitor scrolls. This document records how each is cut and encoded, and how to replace either one.

## How the site uses video

**Hero film.** `dist/assets/video/hub-film.webm` and `.mp4` (`hub-film-720.*` on phones) play muted, looped and inline behind the hero copy: a 30-second generated film of the hub, cropped to 1280×656 to remove the generator's watermark, with a short dip to black at each end so the loop seam reads as a cut. Its opening frame, `dist/assets/hub-film-poster.webp`, is the poster and stands in wherever the film cannot play. As on terafab.ai the film leads: a soft fade from the left carries a short headline, one line and two compact buttons, and the rest of the frame stays open. It is skipped under reduced motion and when the browser reports Save-Data or a 2G connection, pauses while the hero is off screen or a dialog is open, and its own soundtrack can be turned on from the hero's bottom bar. To replace it, encode the new cut with the same names and crop or scale it to the same 1.95:1 frame.

**Journey film.** A 25-second generated film of one visit replaces the drawn car. It is cut at its own shot changes into one clip per chapter, and the scroll position is its clock: each chapter's clip plays across that chapter's share of the scroll, forwards going down and backwards going up. The clip changes exactly when the chapter copy does, and the clips either side wait on the frame they meet it at, so each cut is clean in both directions.

| Chapter | File | Source frames (24 fps) | Source time | What it shows |
|---|---|---|---|---|
| 01 Check-in | `journey-check-in` | 0 to 119 | 0 to 5 s | The car drives across the forecourt and under the lit canopy |
| 02 Inspection | `journey-inspection` | 120 to 239 | 5 to 10 s | A technician at the front, then the bonnet up |
| 03 Service | `journey-service` | 240 to 383 | 10 to 16 s | The car on the lift, technicians underneath |
| 04 Car wash | `journey-wash` | 384 to 504 | 16 to 21.04 s | Water sheeting over the bodywork, then the clean car |
| 05 Handover | `journey-handover` | 505 to 600 | 21.04 to 25.04 s | The car back at the entrance with a member of the team |

Each clip is an MP4 at 1280×656 and a 720-pixel copy for phones (`-720.mp4`), with its opening frame as a WebP still (`journey-*.webp`). The markup in `dist/index.html` names all three per chapter (`data-src`, `data-mobile-src`, `data-poster`); `app.js` attaches them only as the journey comes within 60% of a screen of view, so nothing competes with the hero. On Save-Data or a 2G connection only the stills load, and they still change with the chapters. Under reduced motion and without JavaScript the film is hidden and each chapter shows its still in the reading flow.

On desktops and landscape screens the film fills the stage behind the copy, under a fade from the left. On portrait tablets and phones it is a band between the copy and the stage navigation.

## Encoding recipe

ffmpeg is not installed on the Mac; run it in Docker. Replace the paths.

Loops (the hero): VP9 and H.264, no audio, web-optimised.

```sh
docker run --rm -v "$PWD":/w lscr.io/linuxserver/ffmpeg:latest -y -i /w/source.mov -an -vf "scale=1920:-2,fps=24,format=yuv420p" -c:v libvpx-vp9 -b:v 0 -crf 33 -row-mt 1 -deadline good -cpu-used 2 /w/hub-film.webm
docker run --rm -v "$PWD":/w lscr.io/linuxserver/ffmpeg:latest -y -i /w/source.mov -an -vf "scale=1920:-2,fps=24,format=yuv420p" -c:v libx264 -preset slow -crf 23 -profile:v high -movflags +faststart /w/hub-film.mp4
```

Scrubbed clips (the journey): H.264 only, with a keyframe every 8 frames and no B-frames, so a seek decodes at most seven frames. Every-frame keyframes came out nearly twice the size (11 MB for the five shots), and VP9 with every-frame keyframes larger still (22 MB), so there is no WebM. Trim by frame number, crop off the watermark, then encode each shot at both sizes and take its first frame as the still. For the check-in shot (frames 0 to 119):

```sh
cut="trim=start_frame=0:end_frame=120,setpts=PTS-STARTPTS,crop=1280:656:0:0"
docker run --rm -v "$PWD":/w lscr.io/linuxserver/ffmpeg:latest -y -i /w/visit.mp4 -an -vf "$cut,format=yuv420p" -c:v libx264 -preset slow -crf 24 -g 8 -keyint_min 8 -bf 0 -sc_threshold 0 -profile:v high -movflags +faststart /w/journey-check-in.mp4
docker run --rm -v "$PWD":/w lscr.io/linuxserver/ffmpeg:latest -y -i /w/visit.mp4 -an -vf "$cut,scale=720:-2,format=yuv420p" -c:v libx264 -preset slow -crf 25 -g 8 -keyint_min 8 -bf 0 -sc_threshold 0 -profile:v high -movflags +faststart /w/journey-check-in-720.mp4
docker run --rm -v "$PWD":/w lscr.io/linuxserver/ffmpeg:latest -y -i /w/visit.mp4 -vf "trim=start_frame=0:end_frame=1,crop=1280:656:0:0" -frames:v 1 -q:v 78 /w/journey-check-in.webp
```

To find the cut points of a new film, list its shot changes:

```sh
docker run --rm -v "$PWD":/w lscr.io/linuxserver/ffmpeg:latest -i /w/visit.mp4 -an -vf "select='gt(scene,0.22)',showinfo" -f null - 2>&1 | grep -o 'pts_time:[0-9.]*'
```

Budget: about 20 MB for the whole page. The hero film is 6.5 MB as MP4 and 4.3 MB as WebM at 1280 px, 2 MB at 720 px; the five journey shots are 6.2 MB together at 1280 px and 2.4 MB at 720 px. Vercel serves files up to 25 MB each; `verify-site.cjs` checks the simulation stays under that.

## Replacing the journey film

1. Generate the visit as one continuous film with a shot for each chapter, in order: arrival, inspection, service, wash, handover. Keep the 1.95:1 frame in mind: the left third sits under the copy on desktops, so keep the action centre and right.
2. Find the shot changes, encode one clip per chapter with the recipe above and keep the file names, so nothing else changes. If a chapter needs two shots, keep them in one clip.
3. Run `npm run preview` (or `npx serve dist` for the scrub checks), then `npm test` and `npm run screenshots`, and compare the chapters against `qa-output/`.

## Still open

Ambient loops for the coffee lounge and the work pod would bring the experience dialogs to life: 8 seconds each, seamless, almost nothing moving (steam off the flat white, light drifting across the pod glass, a tiny dolly in). Prompt, with `coffee.webp` or `work.webp` as the image input: "Ambient interior loop of this room. Almost nothing moves: steam rises from the coffee, light drifts across the glass, a plant leaf stirs. Tiny slow dolly in. Photoreal, seamless loop, no text." Encode them like the hero film at about 1 MB each.
