(ns douga.ffmpeg
  "Pure video-assembly (dougaka) planning — timeline -> ffmpeg render plan + command builders. No ffmpeg, network, or blob IO here."
  (:require [clojure.string :as str]))

(def ^:private res-aliases
  {"720p" [1280 720]
   "1080p" [1920 1080]
   "480p" [854 480]})

(defn parse-resolution
  ([res] (parse-resolution res [1280 720]))
  ([res default]
   (let [s (-> (or res "") str/trim str/lower-case)]
     (if (str/includes? s "x")
       (try
         (let [[w h] (str/split s #"x" 2)]
           [(parse-long w) (parse-long h)])
         (catch #?(:clj Exception :cljs :default) _ default))
       (get res-aliases s default)))))

(defn- v [m & ks]
  (some #(get m %) ks))

(defn- scene-index [scene]
  (long (or (v scene :index :scene_index "index" "scene_index") 0)))

(defn build-render-plan
  "Timeline -> ordered render plan. Mirrors the old Python build_render_plan:
  scenes without a composite scene frame are skipped; voices are ordered by line."
  [timeline]
  (let [timeline (or timeline {})
        scenes (or (v timeline :scenes "scenes") [])
        lines (or (v timeline :lines "lines") [])
        assets (or (v timeline :assets "assets") [])
        want-v2 (boolean (v timeline :faceLayers :face-layers "faceLayers" "face_layers"))
        v2-layout "kamishibai-cyber-v2-stage"
        {:keys [v2 baked bgm]}
        (reduce
         (fn [acc a]
           (let [kind (v a :kind "kind")
                 blob (or (v a :blobKey :blob-key "blobKey" "blob_key") "")
                 meta (let [m (v a :meta "meta")] (if (map? m) m {}))
                 si (v meta :sceneIndex :scene-index "sceneIndex" "scene_index")]
             (cond
               (and (= kind "scene") (seq blob) (some? si))
               (if (= (v meta :layout "layout") v2-layout)
                 (assoc-in acc [:v2 (long si)] blob)
                 (assoc-in acc [:baked (long si)] blob))

               (and (= kind "bgm") (seq blob) (nil? (:bgm acc)))
               (assoc acc :bgm blob)

               :else acc)))
         {:v2 {} :baked {} :bgm nil}
         assets)
        frame-by-scene (if want-v2 (merge baked v2) (merge v2 baked))
        voices
        (reduce
         (fn [acc line]
           (let [si (long (or (v line :sceneIndex :scene-index "sceneIndex" "scene_index") 0))
                 li (long (or (v line :lineIndex :line-index "lineIndex" "line_index") 0))
                 vk (v line :voiceBlobKey :voice-blob-key "voiceBlobKey" "voice_blob_key")
                 spk (-> (or (v line :speaker "speaker") "left") str str/trim str/lower-case)
                 text (-> (or (v line :text "text") "") str str/trim)]
             (if (seq vk)
               (update acc si (fnil conj []) [li vk text spk])
               acc)))
         {}
         lines)
        order (sort (set (concat (map scene-index scenes) (keys frame-by-scene))))
        segments
        (vec
         (keep
          (fn [si]
            (when-let [frame (get frame-by-scene si)]
              (let [ordered (sort-by first (get voices si []))]
                {:scene-index si
                 :frame-blob-key frame
                 :voice-blob-keys (mapv second ordered)
                 :texts (mapv #(nth % 2) ordered)
                 :speakers (mapv #(nth % 3) ordered)})))
          order))
        [w h] (parse-resolution (or (v timeline :resolution "resolution") "1280x720"))]
    {:segments segments
     :bgm-blob-key bgm
     :width w
     :height h
     :fps (long (or (v timeline :fps "fps") 30))}))

(defn concat-audio-cmd [wav-paths out-path]
  (let [n (count wav-paths)
        streams (apply str (map-indexed (fn [i _] (str "[" i ":a]")) wav-paths))]
    (vec (concat ["ffmpeg" "-y"]
                 (mapcat (fn [p] ["-i" p]) wav-paths)
                 ["-filter_complex" (str streams "concat=n=" n ":v=0:a=1[a]")
                  "-map" "[a]" out-path]))))

(defn silent-audio-cmd
  ([out-path] (silent-audio-cmd out-path {:seconds 3.0 :sample-rate 24000}))
  ([out-path {:keys [seconds sample-rate] :or {seconds 3.0 sample-rate 24000}}]
   ["ffmpeg" "-y" "-f" "lavfi"
    "-i" (str "anullsrc=r=" sample-rate ":cl=mono") "-t" (str seconds) out-path]))

(defn scene-segment-cmd [frame-path audio-path out-path {:keys [width height fps]}]
  ["ffmpeg" "-y" "-loop" "1" "-i" frame-path "-i" audio-path
   "-vf" (str "scale=" width ":" height ":force_original_aspect_ratio=decrease,"
              "pad=" width ":" height ":(ow-iw)/2:(oh-ih)/2,setsar=1")
   "-r" (str fps) "-shortest" "-c:v" "libx264" "-pix_fmt" "yuv420p" "-c:a" "aac" out-path])

(defn concat-segments-cmd [list-path out-path]
  ["ffmpeg" "-y" "-f" "concat" "-safe" "0" "-i" list-path "-c" "copy" out-path])

(defn xfade-transition-cmd
  "A real crossfade merge of two adjacent clips bridged by a
  kami.eizo.timeline `:dissolve` transition, into ONE continuous output
  video via ffmpeg's `xfade` filter — douga's dissolve render path (the
  hard-cut path is scene-segment-cmd + concat-segments-cmd; this is the
  alternative for a transition-bridged pair, see douga.eizo-timeline
  render-plan's :video-transitions and this repo's README).

  from-frame-path / to-frame-path: still-image sources for clip A / clip B
  (looped for their own full natural duration, same convention as
  scene-segment-cmd's frame-path argument).

  opts:
    :width :height :fps          -- same as scene-segment-cmd.
    :from-duration-frames        -- clip A's OWN full duration in frames
                                     (kami.eizo.timeline :clip/duration --
                                     NOT overlap-adjusted).
    :to-duration-frames          -- clip B's OWN full duration in frames.
    :transition-duration-frames  -- the dissolve's duration in frames
                                     (kami.eizo.timeline :transition/duration).

  kami.eizo.timeline's overlap semantics (kami.eizo.timeline/validate-transition:
  clip B's :clip/timeline-start = clip A's clip-end - transition duration,
  i.e. the two clips overlap by exactly the transition's duration) map
  directly onto ffmpeg xfade's own offset/duration semantics: offset (the
  point in stream 0 where the crossfade begins) = duration-a - transition
  duration; duration = transition duration. Total output duration =
  duration-a + duration-b - transition duration — the same
  overlap-consumes-shared-frames arithmetic kami.eizo.timeline already
  uses, so a plan built from an already-validated EDL never needs to
  re-derive it.

  `transition=fade` is a genuine linear alpha crossfade, not a hard cut or
  a fade-to-black: verified empirically (see test/e2e/real_dissolve_proof.cljs
  and this repo's README) — a red->blue dissolve samples ~50/50 R/B channel
  contribution at the transition's 50% point, and the blend shifts
  monotonically across the transition window."
  [from-frame-path to-frame-path out-path
   {:keys [width height fps from-duration-frames to-duration-frames transition-duration-frames]}]
  (let [from-s (/ (double from-duration-frames) fps)
        to-s (/ (double to-duration-frames) fps)
        trans-s (/ (double transition-duration-frames) fps)
        offset-s (- from-s trans-s)]
    (when (neg? offset-s)
      (throw (ex-info "douga.ffmpeg/xfade-transition-cmd: transition-duration-frames exceeds from-duration-frames"
                       {:transition-duration-frames transition-duration-frames
                        :from-duration-frames from-duration-frames})))
    ["ffmpeg" "-y"
     "-loop" "1" "-t" (str from-s) "-i" from-frame-path
     "-loop" "1" "-t" (str to-s) "-i" to-frame-path
     "-filter_complex"
     (str "[0:v]scale=" width ":" height ":force_original_aspect_ratio=decrease,"
          "pad=" width ":" height ":(ow-iw)/2:(oh-ih)/2,setsar=1,fps=" fps "[v0];"
          "[1:v]scale=" width ":" height ":force_original_aspect_ratio=decrease,"
          "pad=" width ":" height ":(ow-iw)/2:(oh-ih)/2,setsar=1,fps=" fps "[v1];"
          "[v0][v1]xfade=transition=fade:duration=" trans-s ":offset=" offset-s "[v]")
     "-map" "[v]" "-r" (str fps) "-c:v" "libx264" "-pix_fmt" "yuv420p" out-path]))

(defn bgm-mix-cmd [video-path bgm-path out-path]
  ["ffmpeg" "-y" "-i" video-path "-stream_loop" "-1" "-i" bgm-path
   "-filter_complex" "[0:a][1:a]amix=inputs=2:duration=first:dropout_transition=0[a]"
   "-map" "0:v" "-map" "[a]" "-c:v" "copy" "-c:a" "aac" out-path])

(defn concat-list-text [paths]
  (apply str (for [p paths]
               (str "file '" (str/replace p #"'" (constantly "'\\''")) "'\n"))))
