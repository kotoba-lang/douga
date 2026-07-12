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

## Occupation

ISCO-08 `2654` (Film, Stage and Related Directors and Producers) —
[cloud-itonami-isco-2654](https://github.com/cloud-itonami/cloud-itonami-isco-2654).

## Test

```bash
clojure -M:test
```

## License

Apache-2.0.
