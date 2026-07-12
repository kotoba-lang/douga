# douga

Pure video-assembly (動画) planning — the craft library split out of
gftdcojp's private `ai-gftd-dougaka` actor (ADR-2607023000: コードは
kotoba-lang、職能は cloud-itonami-isco、商売は gftdcojp).

`douga.ffmpeg` turns a timeline (scenes + voice lines + frame/bgm assets) into
an ordered render plan and builds ffmpeg command vectors. **No IO**: nothing
here shells out, touches the network, or reads blobs — callers execute the
returned command vectors and attach blob storage themselves.

## Contract

```clojure
(require '[douga.ffmpeg :as ffmpeg])

(ffmpeg/build-render-plan timeline)
;; => {:segments [{:scene-index 0 :frame-blob-key "..." :voice-blob-keys [...]
;;                 :texts [...] :speakers [...]}]
;;     :bgm-blob-key "..." :width 1280 :height 720 :fps 30}

(ffmpeg/scene-segment-cmd frame-path audio-path out-path
                          {:width 1280 :height 720 :fps 30})
;; => ["ffmpeg" "-y" ...]
```

Also: `parse-resolution`, `concat-audio-cmd`, `silent-audio-cmd`,
`concat-segments-cmd`, `bgm-mix-cmd`, `concat-list-text`, and
`xfade-transition-cmd` (real dissolve-transition rendering, see below).

Timeline keys are read leniently (`:sceneIndex` / `:scene-index` /
`"scene_index"` …) so plans built from JSON, EDN, or kebab-case sources all
work.

## `kami-eizo-timeline` input (ADR-2607121400 Wave 4)

`douga.eizo-timeline` is a second, parallel entry point that consumes a
real [`kami.eizo.timeline`](https://github.com/kotoba-lang/kami-eizo-timeline)
EDL value instead of the loose scene/lines/assets shape above, and produces
the *same* render-plan output — so all the existing command builders
(`scene-segment-cmd`, `concat-segments-cmd`, `bgm-mix-cmd`, …) keep working
unchanged downstream. The legacy `douga.ffmpeg/build-render-plan` path is
untouched and still backs the live yukkuri production pipeline
(ADR-2607051600) — this is an addition, not a replacement.

```clojure
(require '[kami.eizo.timeline :as tl]
         '[kami.eizo.timeline.timecode :as tc]
         '[douga.eizo-timeline :as det])

(def video-track
  (tl/track {:id :video-1 :type :video
             :clips [(det/scene-clip {:scene-index 0 :frame-blob-key "frame-0"
                                       :timeline-start 0 :duration-frames 96})]}))
(def audio-track
  (tl/track {:id :audio-1 :type :audio
             :clips [(det/voice-clip {:scene-index 0 :line-index 0
                                       :voice-blob-key "v-0-0" :text "hi" :speaker "left"
                                       :timeline-start 0 :duration-frames 48})]}))
(def timeline
  (assoc (tl/timeline {:timebase (tc/make-timebase 24 1 false)
                        :tracks [video-track audio-track]
                        :markers [(det/bgm-marker* {:bgm-blob-key "bgm-key" :duration-frames 96})]})
         :douga/resolution "1080p"))

(det/render-plan timeline)
;; => {:segments [...] :bgm-blob-key "bgm-key" :width 1920 :height 1080 :fps 24}
```

Mapping: one `:video`-track clip per scene, one `:audio`-track clip per
voice line (ordered by `:douga/scene-index`/`:douga/line-index`), bgm as a
single `"bgm"`-named timeline marker, fps from the timebase (rounded via
`kami.eizo.timeline.timecode/frame-rate-round`), resolution from an extra
`:douga/resolution` key on the timeline map (kami-eizo-timeline's EDL has
no resolution concept of its own).

**Real EDL clips need real durations**: unlike the legacy path (which lets
ffmpeg discover each scene's actual duration at render time via
`-shortest`), a `kami.eizo.timeline` clip's `:clip/source-out` must be a
known frame count up front — supply it from a duration-estimation or
pre-rendered-audio pass if you want this entry point.

**`:dissolve` video-track transitions now render for real** (ADR-2607121400
Wave 5): `render-plan` no longer rejects a `:dissolve` transition between
two adjacent `:video`-track clips — it surfaces it as a `:video-transitions`
entry, and `douga.ffmpeg/xfade-transition-cmd` turns that into a real
ffmpeg `xfade` `filter_complex` crossfade (see "Real dissolve-transition
render proof" below). Other transition types (`:wipe`/etc.) are still
rejected with `ex-info`, not silently ignored or silently downgraded to a
hard cut — only `:dissolve` has a render path today.

```clojure
;; render-plan surfaces a :dissolve transition (kami.eizo.timeline overlap
;; semantics: clip B's :clip/timeline-start = clip A's clip-end - transition
;; duration) as :video-transitions, keyed by scene-index:
(det/render-plan timeline)
;; => {:segments [{:scene-index 0 :duration-frames 96 ...}
;;                {:scene-index 1 :duration-frames 96 ...}]
;;     :video-transitions [{:from-scene-index 0 :to-scene-index 1 :duration-frames 48}]
;;     ...}

;; feed the bridged pair + the transition's duration to xfade-transition-cmd:
(ffmpeg/xfade-transition-cmd frame-a-path frame-b-path out-path
                             {:width 320 :height 240 :fps 24
                              :from-duration-frames 96
                              :to-duration-frames 96
                              :transition-duration-frames 48})
;; => ["ffmpeg" "-y" "-loop" "1" "-t" "4" "-i" frame-a-path
;;     "-loop" "1" "-t" "4" "-i" frame-b-path
;;     "-filter_complex" "...xfade=transition=fade:duration=2:offset=2[v]..."
;;     "-map" "[v]" "-r" "24" "-c:v" "libx264" "-pix_fmt" "yuv420p" out-path]
```

## Real-ffmpeg execution proof (`test/e2e/`)

douga is deliberately I/O-free: `douga.ffmpeg` / `douga.eizo-timeline` only
*return* ffmpeg command vectors, they never shell out. `test/douga/*_test.cljc`
(run via `clojure -M:test`) only assert the **shape** of those vectors (right
flags, right ordering) — nothing in this repo's own test suite has ever
actually handed a generated command vector to a real `ffmpeg` binary and
checked that it runs and produces correct output.

`test/e2e/real_ffmpeg_proof.cljs` (nbb) closes that gap. It:

1. builds a real [`kami.eizo.timeline`](https://github.com/kotoba-lang/kami-eizo-timeline)
   EDL — 3 scenes (red / lime / blue), 2.0s / 1.5s / 2.5s long at 24fps,
   placed back-to-back on the `:video` track with **no transitions** (a hard
   cut — the only kind `douga.eizo-timeline` supports, see "v0 gap" above),
   each with a matching-duration voice tone on the `:audio` track and a bgm
   marker;
2. generates the scenes' still-frame and voice/bgm audio sources locally via
   `ffmpeg -f lavfi` (`color=`/`sine=` — no external assets, no network);
3. drives that EDL through the **real** `douga.eizo-timeline/render-plan` and
   the **real** `douga.ffmpeg/scene-segment-cmd` /
   `douga.ffmpeg/concat-segments-cmd` / `douga.ffmpeg/bgm-mix-cmd` command
   builders (nothing hand-typed — the argv vectors executed are exactly what
   douga's own code returns);
4. executes every returned command vector as a real `ffmpeg` child process,
   synchronously, and checks each output file actually exists and is
   non-empty;
5. verifies the final assembled video with real `ffprobe` (duration,
   dimensions) and by extracting real pixels at 9 timestamps (including right
   before/after each hard cut) via a real `ffmpeg` decode + crop, comparing
   the sampled color against whichever scene
   `kami.eizo.timeline/clip-at-frame` says should be showing at that frame —
   i.e. it doesn't just check "some video came out", it checks the right
   color is on screen at the right time, cross-referenced against the EDL's
   own frame-lookup query.

Last verified run: all 23 checks passed — 320×240 output, ~6.06s duration
(expected 6.00s; the ~60ms delta is normal concat-demuxer `-c copy` DTS
rounding from B-frame reordering across segment boundaries, not a douga
defect), and every sampled pixel matched its expected scene color within a
40/255-channel tolerance (actual observed drift was 1–3/255, from ordinary
H.264/yuv420p lossy encoding of a flat color field).

Requires system `ffmpeg` + `ffprobe` on `PATH`, and a local checkout of
`kotoba-lang/kami-eizo-timeline` (the sha this repo's `deps.edn` pins) whose
`src/` is reachable via classpath:

```bash
nbb -cp src:<path-to-kami-eizo-timeline>/src test/e2e/real_ffmpeg_proof.cljs
```

Exits 0 with a PASS report on success, 1 with the failing checks printed on
failure.

## Real dissolve-transition render proof (`test/e2e/real_dissolve_proof.cljs`)

Wave-8's `real_ffmpeg_proof.cljs` above deliberately only covers hard cuts —
that was `douga.eizo-timeline`'s entire v0 scope, and a `:dissolve`
video-track transition was rejected with `ex-info` rather than silently
ignored. `test/e2e/real_dissolve_proof.cljs` is the same real-subprocess
proof pattern applied to the render path that closes that gap: a real
`xfade` crossfade, driven through the real `douga.eizo-timeline/render-plan`
and the real `douga.ffmpeg/xfade-transition-cmd`, not a hand-typed ffmpeg
invocation.

It:

1. builds a real `kami.eizo.timeline` EDL — a 4.0s red clip and a 4.0s blue
   clip, bridged by a 2.0s `:dissolve` transition. Per
   `kami.eizo.timeline`'s overlap semantics (`validate-transition`: clip B's
   `:clip/timeline-start` = clip A's `clip-end` minus the transition's
   duration), the two clips overlap by exactly 2.0s, so the EDL's total
   duration is 4.0+4.0-2.0=**6.0s**, not 8.0s — the proof asserts this via
   `kami.eizo.timeline/timeline-duration` before rendering anything, so a
   regression that ignored the overlap (silently concatenating full clip
   durations) would be caught before it ever reached ffmpeg;
2. generates the two solid-color stills locally via `ffmpeg -f lavfi
   color=...` (no external assets, no network);
3. drives that EDL through the **real** `douga.eizo-timeline/render-plan`
   (which no longer throws for this — confirming the v0 rejection is gone)
   and asserts the returned `:video-transitions` entry matches the EDL's
   transition exactly;
4. builds the **real** `douga.ffmpeg/xfade-transition-cmd` argv from the
   render-plan's segments and executes it as a single real `ffmpeg` child
   process (one invocation produces the entire crossfade-merged output —
   no separate per-clip render + concat step, since `-c copy` concat can't
   express a blend);
5. verifies with real `ffprobe` that the output is exactly 6.0s (not 8.0s),
   at the right resolution;
6. samples real decoded pixels at 5 checkpoints — before the transition
   window, and at 25%/50%/75%/after within/past it — and asserts: pure red
   before, pure blue after, and at 25/50/75% a **genuine, monotonically
   shifting blend** (R strictly decreasing, B strictly increasing across
   all 5 checkpoints, G staying ~0 throughout with no corruption), with an
   almost-exactly-even 50/50 R/B split at the transition's midpoint — this
   is the actual proof that a crossfade is happening, not a hard cut
   (which would show pure red until 4.0s then instantly pure blue) and not
   a broken/no-op filter (which would show a corrupted or black frame).

Last verified run: all 17 checks passed — 320×240 output, exactly 6.000000s
(ffprobe), and real sampled RGB at each checkpoint: `#fc0000` (before,
t=1.0s) → `#bc003d` (25%, t=2.5s) → `#7d007d` (50%, t=3.0s, an almost exact
125/125 R/B split) → `#3d00be` (75%, t=3.5s) → `#0000fd` (after, t=5.0s).
The non-255/non-0 pure-color values (252/253 instead of 255, and the
symmetric-but-not-exactly-half 188/61 and 61/190 at 25%/75%) are ordinary
yuv420p/H.264 lossy-encoding drift from `libx264`, not a douga or filter
defect.

Requires system `ffmpeg` + `ffprobe` on `PATH`, and a local checkout of
`kotoba-lang/kami-eizo-timeline` whose `src/` is reachable via classpath:

```bash
nbb -cp src:<path-to-kami-eizo-timeline>/src test/e2e/real_dissolve_proof.cljs
```

Exits 0 with a PASS report on success, 1 with the failing checks printed on
failure.

**Maturity note**: this proves the dissolve render path for a single
transition between exactly two clips on a video-only timeline. Multi-clip
timelines with several transitions, transitions combined with per-scene
audio/bgm muxing, and other transition types (`:wipe`/etc.) are not yet
wired end-to-end — see `douga.eizo-timeline`'s `:video-transitions` output
(one entry per transition) as the extension point for a caller wanting to
chain several dissolves across more than two clips.

## Occupation

ISCO-08 `2654` (Film, Stage and Related Directors and Producers) —
[cloud-itonami-isco-2654](https://github.com/cloud-itonami/cloud-itonami-isco-2654).

## Test

```bash
clojure -M:test
```

## License

Apache-2.0.
