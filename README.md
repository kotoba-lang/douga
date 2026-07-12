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
`concat-segments-cmd`, `bgm-mix-cmd`, `concat-list-text`.

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

**v0 gap**: video-track transitions (dissolve/wipe/etc.) are rejected
(`ex-info`), not silently ignored — douga's ffmpeg builders only ever
produce hard cuts today. Wiring dissolve/wipe to a real `xfade`
`filter_complex` is a follow-up, not part of this rewire.

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

## Occupation

ISCO-08 `2654` (Film, Stage and Related Directors and Producers) —
[cloud-itonami-isco-2654](https://github.com/cloud-itonami/cloud-itonami-isco-2654).

## Test

```bash
clojure -M:test
```

## License

Apache-2.0.
