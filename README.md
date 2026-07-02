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

## Occupation

ISCO-08 `2654` (Film, Stage and Related Directors and Producers) —
[cloud-itonami-isco-2654](https://github.com/cloud-itonami/cloud-itonami-isco-2654).

## Test

```bash
clojure -M:test
```

## License

Apache-2.0.
