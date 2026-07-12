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

(def ^:private xfade-mode-by-transition-type
  "kami.eizo.timeline :transition/type -> ffmpeg xfade's `transition=` mode
  string. :dissolve -> `fade` (a genuine linear alpha crossfade, a temporal
  blend). :wipe -> `wipeleft` (a moving spatial boundary: clip A stays put
  on the right, clip B is revealed by a hard edge sweeping right-to-left as
  the transition progresses — NOT a blend; verified empirically against the
  installed ffmpeg's `xfade` filter, see test/e2e/real_wipe_proof.cljs and
  this repo's README). Only these two have a douga render path today —
  same gate as douga.eizo-timeline's supported-transition-types."
  {:dissolve "fade"
   :wipe "wipeleft"})

(defn xfade-transition-cmd
  "A real merge of two adjacent clips bridged by a kami.eizo.timeline
  `:dissolve` or `:wipe` transition, into ONE continuous output video via
  ffmpeg's `xfade` filter — douga's transition render path (the hard-cut
  path is scene-segment-cmd + concat-segments-cmd; this is the alternative
  for a transition-bridged pair, see douga.eizo-timeline render-plan's
  :video-transitions and this repo's README).

  from-frame-path / to-frame-path: still-image sources for clip A / clip B
  (looped for their own full natural duration, same convention as
  scene-segment-cmd's frame-path argument).

  opts:
    :width :height :fps          -- same as scene-segment-cmd.
    :from-duration-frames        -- clip A's OWN full duration in frames
                                     (kami.eizo.timeline :clip/duration --
                                     NOT overlap-adjusted).
    :to-duration-frames          -- clip B's OWN full duration in frames.
    :transition-duration-frames  -- the transition's duration in frames
                                     (kami.eizo.timeline :transition/duration).
    :transition-type             -- :dissolve (default, backward compatible)
                                     or :wipe -- looked up in
                                     xfade-mode-by-transition-type to pick
                                     xfade's `transition=` mode. Unknown
                                     types throw ex-info (same
                                     fail-loud-not-silent-downgrade
                                     discipline as douga.eizo-timeline's
                                     supported-transition-types gate).

  kami.eizo.timeline's overlap semantics (kami.eizo.timeline/validate-transition:
  clip B's :clip/timeline-start = clip A's clip-end - transition duration,
  i.e. the two clips overlap by exactly the transition's duration) map
  directly onto ffmpeg xfade's own offset/duration semantics: offset (the
  point in stream 0 where the transition begins) = duration-a - transition
  duration; duration = transition duration. Total output duration =
  duration-a + duration-b - transition duration — the same
  overlap-consumes-shared-frames arithmetic kami.eizo.timeline already
  uses, so a plan built from an already-validated EDL never needs to
  re-derive it. This offset/duration arithmetic is identical for
  :dissolve and :wipe — only the xfade `transition=` mode differs.

  `transition=fade` (:dissolve) is a genuine linear alpha crossfade, not a
  hard cut or a fade-to-black: verified empirically (see
  test/e2e/real_dissolve_proof.cljs and this repo's README) — a red->blue
  dissolve samples ~50/50 R/B channel contribution at the transition's 50%
  point, and the blend shifts monotonically across the transition window.
  `transition=wipeleft` (:wipe) is a genuine moving spatial boundary, not a
  blend: verified empirically (see test/e2e/real_wipe_proof.cljs and this
  repo's README) — at a fixed timestamp mid-transition, sampling pixels
  across the frame's width shows clip A's solid color on the (still
  unrevealed) right portion and clip B's solid color on the (already
  revealed) left portion, with a sharp boundary between them (not a
  gradient), and that boundary's x-position moves leftward as the
  transition progresses through 25%/50%/75%."
  [from-frame-path to-frame-path out-path
   {:keys [width height fps from-duration-frames to-duration-frames
           transition-duration-frames transition-type]
    :or {transition-type :dissolve}}]
  (let [xfade-mode (get xfade-mode-by-transition-type transition-type)
        _ (when-not xfade-mode
            (throw (ex-info (str "douga.ffmpeg/xfade-transition-cmd: transition-type "
                                  (pr-str transition-type) " has no ffmpeg xfade mode "
                                  "mapping (known: " (pr-str (set (keys xfade-mode-by-transition-type))) ")")
                             {:transition-type transition-type})))
        from-s (/ (double from-duration-frames) fps)
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
          "[v0][v1]xfade=transition=" xfade-mode ":duration=" trans-s ":offset=" offset-s "[v]")
     "-map" "[v]" "-r" (str fps) "-c:v" "libx264" "-pix_fmt" "yuv420p" out-path]))

(defn xfade-chain-cmd
  "N-clip chained-transition render — the generalization of
  xfade-transition-cmd for a video track with TWO OR MORE sequential
  transitions bridging THREE OR MORE clips in a row (e.g. clip1 --T1-->
  clip2 --T2--> clip3 --T3--> clip4 ...), into ONE continuous output video
  via a single ffmpeg invocation with N-1 chained `xfade` filter_complex
  stages. xfade-transition-cmd itself only ever builds a 2-input, 1-stage
  graph — it has no notion of chaining, and looping it per-pair would not
  produce a correct chained graph (see the offset arithmetic below), which
  is why this is a separate function rather than a wrapper around it.

  THE CHAINING GOTCHA this function exists to get right: ffmpeg's `xfade`
  `offset` is relative to the START of its own first input stream. For
  stage 1 that first input is clip 1 itself, so offset_1 is the same
  `from-duration - transition-duration` arithmetic as xfade-transition-cmd.
  But stage 2's first input is NOT clip 2 or clip 1 alone — it is stage 1's
  OUTPUT stream, whose duration is already `duration(clip1) + duration(clip2)
  - duration(T1)` (clip 1's and clip 2's combined, overlap-consumed length).
  A naive per-pair loop that reused each clip's own raw duration for every
  stage's offset would therefore get every stage after the first WRONG.
  The correct offset is computed against the chain's CUMULATIVE output
  duration so far, matching kami.eizo.timeline's own chained-overlap
  `timeline-duration` arithmetic exactly (validate-transition's overlap
  invariant applied transitively down the chain, not just pairwise):

    D_0 = duration(clip_0)
    D_k = D_(k-1) + duration(clip_k) - duration(transition_k)   (k = 1..n-1)
    offset_k = D_(k-1) - duration(transition_k)                 (stage k, 1-indexed)

  Each stage k's filter_complex clause therefore chains onto the PREVIOUS
  stage's output label, not a raw source label: stage 1 is
  `[v0][v1]xfade=...:offset=offset_1[vx1]`, stage 2 is
  `[vx1][v2]xfade=...:offset=offset_2[vx2]` (or `[v]` if it's the final
  stage), and so on — never `[v1][v2]xfade=...` for stage 2, which would
  silently discard the accumulated overlap from stage 1.

  segments: ordered vector of N maps `{:frame-blob-key :duration-frames}`,
  one per clip, in timeline order — the same shape
  `douga.eizo-timeline/render-plan`'s `:segments` already carries.
  transitions: ordered vector of exactly (count segments)-1 maps
  `{:duration-frames :transition-type}`; transitions[k] (0-indexed) bridges
  segments[k] and segments[k+1] — the same adjacency
  `douga.eizo-timeline/render-plan`'s `:video-transitions` already carries
  for a video track with sequential transitions (sort by
  `:from-scene-index` first if a track's transitions weren't authored in
  timeline order). `:transition-type` defaults to `:dissolve` per entry,
  same as xfade-transition-cmd; unknown types throw ex-info up front
  (before any ffmpeg argv is built), same fail-loud discipline.
  opts: :width :height :fps — same as xfade-transition-cmd.

  Total output duration = sum of every clip's own duration minus the sum
  of every transition's duration (each transition consumes exactly its own
  duration of shared overlap, once, chain-wide — never double-consumed
  even when two transitions both touch the same middle clip)."
  [segments transitions out-path {:keys [width height fps]}]
  (let [n (count segments)]
    (when (< n 2)
      (throw (ex-info "douga.ffmpeg/xfade-chain-cmd: need at least 2 segments"
                       {:segments n})))
    (when (not= (count transitions) (dec n))
      (throw (ex-info "douga.ffmpeg/xfade-chain-cmd: need exactly (count segments)-1 transitions"
                       {:segments n :transitions (count transitions)})))
    (doseq [tr transitions]
      (let [tt (or (:transition-type tr) :dissolve)]
        (when-not (get xfade-mode-by-transition-type tt)
          (throw (ex-info (str "douga.ffmpeg/xfade-chain-cmd: transition-type " (pr-str tt)
                                " has no ffmpeg xfade mode mapping (known: "
                                (pr-str (set (keys xfade-mode-by-transition-type))) ")")
                           {:transition-type tt})))))
    (let [durs-s (mapv (fn [seg] (/ (double (:duration-frames seg)) fps)) segments)
          trans-s (mapv (fn [tr] (/ (double (:duration-frames tr)) fps)) transitions)
          inputs (mapcat (fn [seg d-s] ["-loop" "1" "-t" (str d-s) "-i" (:frame-blob-key seg)])
                          segments durs-s)
          scale-filters
          (apply str
                 (map (fn [i]
                        (str "[" i ":v]scale=" width ":" height ":force_original_aspect_ratio=decrease,"
                             "pad=" width ":" height ":(ow-iw)/2:(oh-ih)/2,setsar=1,fps=" fps
                             "[v" i "];"))
                      (range n)))
          last-stage (dec n) ;; stage index k runs 1..n-1; the final stage is k = n-1
          {:keys [filter-str]}
          (reduce
           (fn [{:keys [acc-dur-s filter-str] :as _acc} k]
             (let [t-s (nth trans-s (dec k))
                   offset-s (- acc-dur-s t-s)
                   _ (when (neg? offset-s)
                       (throw (ex-info (str "douga.ffmpeg/xfade-chain-cmd: transition " k
                                             " duration exceeds the chain's accumulated duration so far")
                                        {:stage k :accumulated-duration-s acc-dur-s
                                         :transition-duration-s t-s})))
                   tt (or (:transition-type (nth transitions (dec k))) :dissolve)
                   mode (get xfade-mode-by-transition-type tt)
                   in0 (if (= k 1) "v0" (str "vx" (dec k)))
                   in1 (str "v" k)
                   out-label (if (= k last-stage) "v" (str "vx" k))
                   next-dur-s (- (+ acc-dur-s (nth durs-s k)) t-s)]
               {:acc-dur-s next-dur-s
                :filter-str (str filter-str "[" in0 "][" in1 "]xfade=transition=" mode
                                  ":duration=" t-s ":offset=" offset-s "[" out-label "]"
                                  (when-not (= k last-stage) ";"))}))
           {:acc-dur-s (first durs-s) :filter-str ""}
           (range 1 n))]
      (vec (concat ["ffmpeg" "-y"] inputs
                   ["-filter_complex" (str scale-filters filter-str)
                    "-map" "[v]" "-r" (str fps) "-c:v" "libx264" "-pix_fmt" "yuv420p" out-path])))))

(defn bgm-mix-cmd [video-path bgm-path out-path]
  ["ffmpeg" "-y" "-i" video-path "-stream_loop" "-1" "-i" bgm-path
   "-filter_complex" "[0:a][1:a]amix=inputs=2:duration=first:dropout_transition=0[a]"
   "-map" "0:v" "-map" "[a]" "-c:v" "copy" "-c:a" "aac" out-path])

(defn concat-list-text [paths]
  (apply str (for [p paths]
               (str "file '" (str/replace p #"'" (constantly "'\\''")) "'\n"))))
