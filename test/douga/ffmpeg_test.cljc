(ns douga.ffmpeg-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [douga.ffmpeg :as ffmpeg]))

(deftest parse-resolution-handles-aliases-and-explicit
  (is (= [1920 1080] (ffmpeg/parse-resolution "1080p")))
  (is (= [1280 720] (ffmpeg/parse-resolution "720p")))
  (is (= [640 360] (ffmpeg/parse-resolution "640x360")))
  (is (= [1280 720] (ffmpeg/parse-resolution "garbage")))
  (is (= [1 1] (ffmpeg/parse-resolution nil [1 1]))))

(def timeline
  {:resolution "1080p"
   :fps 24
   :scenes [{:index 0} {:index 1}]
   :assets [{:kind "scene" :blobKey "frame-0" :meta {:sceneIndex 0}}
            {:kind "scene" :blobKey "frame-1" :meta {:sceneIndex 1}}
            {:kind "bgm" :blobKey "bgm-key"}]
   :lines [{:sceneIndex 0 :lineIndex 1 :voiceBlobKey "v-0-1" :text "second" :speaker "Right"}
           {:sceneIndex 0 :lineIndex 0 :voiceBlobKey "v-0-0" :text "first" :speaker "Left"}
           {:sceneIndex 1 :lineIndex 0 :voiceBlobKey "v-1-0" :text "next scene"}]})

(deftest build-render-plan-orders-scenes-and-voices
  (let [plan (ffmpeg/build-render-plan timeline)]
    (is (= 2 (count (:segments plan))))
    (is (= [0 1] (map :scene-index (:segments plan))))
    (let [seg0 (first (:segments plan))]
      (is (= "frame-0" (:frame-blob-key seg0)))
      (is (= ["v-0-0" "v-0-1"] (:voice-blob-keys seg0)))
      (is (= ["first" "second"] (:texts seg0)))
      (is (= ["left" "right"] (:speakers seg0))))
    (is (= "bgm-key" (:bgm-blob-key plan)))
    (is (= 1920 (:width plan)))
    (is (= 1080 (:height plan)))
    (is (= 24 (:fps plan)))))

(deftest build-render-plan-skips-scenes-without-frames
  (let [plan (ffmpeg/build-render-plan (update timeline :assets
                                               (fn [xs] (remove #(= "frame-1" (:blobKey %)) xs))))]
    (is (= [0] (map :scene-index (:segments plan))))))

(deftest build-render-plan-prefers-v2-frames-when-face-layers
  (let [v2-asset {:kind "scene" :blobKey "frame-0-v2"
                  :meta {:sceneIndex 0 :layout "kamishibai-cyber-v2-stage"}}
        tl (-> timeline
               (update :assets conj v2-asset)
               (assoc :faceLayers true))
        plan (ffmpeg/build-render-plan tl)]
    (is (= "frame-0-v2" (:frame-blob-key (first (:segments plan)))))))

(deftest command-builders-shape
  (testing "concat-audio-cmd"
    (let [cmd (ffmpeg/concat-audio-cmd ["a.wav" "b.wav"] "out.wav")]
      (is (= "ffmpeg" (first cmd)))
      (is (some #(str/includes? (str %) "concat=n=2") cmd))))
  (testing "scene-segment-cmd carries resolution and fps"
    (let [cmd (ffmpeg/scene-segment-cmd "f.png" "a.wav" "out.mp4"
                                        {:width 1280 :height 720 :fps 30})]
      (is (some #(str/includes? (str %) "scale=1280:720") cmd))
      (is (some #(= "30" %) cmd))))
  (testing "video-segment-cmd trims moving media and supplies stable audio"
    (let [cmd (ffmpeg/video-segment-cmd "source.mov" "out.mp4"
                                        {:source-start-sec 1.5 :duration-sec 2
                                         :width 1920 :height 1080 :fps 30})]
      (is (= ["ffmpeg" "-y" "-ss" "1.5" "-i" "source.mov"] (subvec cmd 0 6)))
      (is (some #(= "anullsrc=r=48000:cl=stereo" %) cmd))
      (is (some #(str/includes? (str %) "scale=1920:1080") cmd))
      (is (= "out.mp4" (last cmd)))))
  (testing "concat-list-text escapes single quotes"
    (is (= "file 'a.mp4'\n" (ffmpeg/concat-list-text ["a.mp4"])))
    (is (str/includes? (ffmpeg/concat-list-text ["it's.mp4"]) "'\\''"))))

(deftest xfade-transition-cmd-shape
  (testing "builds a real xfade filter_complex crossfade, offset/duration in seconds"
    (let [cmd (ffmpeg/xfade-transition-cmd "a.png" "b.png" "out.mp4"
                                           {:width 320 :height 240 :fps 24
                                            :from-duration-frames 96
                                            :to-duration-frames 96
                                            :transition-duration-frames 48})
          filter-str (str (second (drop-while #(not= "-filter_complex" %) cmd)))]
      (is (= "ffmpeg" (first cmd)))
      (is (= ["a.png" "b.png"] (->> cmd (partition 2 1) (filter #(= "-i" (first %))) (map second))))
      (is (str/includes? filter-str "xfade=transition=fade"))
      ;; duration/offset formatting is clj "2.0" vs cljs "2" (native JS
      ;; number->string) -- assert on the parsed numeric value, not the
      ;; literal string, so this test passes identically on both platforms.
      (let [[_ dur] (re-find #"duration=([0-9.]+):offset=" filter-str)
            [_ off] (re-find #"offset=([0-9.]+)\[v\]" filter-str)]
        (is (= 2.0 #?(:clj (Double/parseDouble dur) :cljs (js/parseFloat dur))))
        (is (= 2.0 #?(:clj (Double/parseDouble off) :cljs (js/parseFloat off)))))
      (is (some #(= "[v]" %) cmd))
      (is (some #(str/includes? (str %) "scale=320:240") cmd))))
  (testing "throws when the transition duration exceeds clip A's own duration"
    (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
                 (ffmpeg/xfade-transition-cmd "a.png" "b.png" "out.mp4"
                                              {:width 320 :height 240 :fps 24
                                               :from-duration-frames 24
                                               :to-duration-frames 96
                                               :transition-duration-frames 48}))))
  (testing ":transition-type :wipe selects xfade's wipeleft mode instead of fade"
    (let [cmd (ffmpeg/xfade-transition-cmd "a.png" "b.png" "out.mp4"
                                           {:width 320 :height 240 :fps 24
                                            :from-duration-frames 96
                                            :to-duration-frames 96
                                            :transition-duration-frames 48
                                            :transition-type :wipe})
          filter-str (str (second (drop-while #(not= "-filter_complex" %) cmd)))]
      (is (str/includes? filter-str "xfade=transition=wipeleft"))
      (is (not (str/includes? filter-str "transition=fade")))))
  (testing "throws for an unknown :transition-type instead of silently falling back to fade"
    (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
                 (ffmpeg/xfade-transition-cmd "a.png" "b.png" "out.mp4"
                                              {:width 320 :height 240 :fps 24
                                               :from-duration-frames 96
                                               :to-duration-frames 96
                                               :transition-duration-frames 48
                                               :transition-type :slide})))))

(deftest xfade-chain-cmd-shape
  (testing "3 clips / 2 chained transitions: builds ONE filter_complex with 2 xfade stages,
           the second stage consuming the first stage's output label (not a raw source)"
    (let [segments [{:frame-blob-key "a.png" :duration-frames 96}   ;; 4.0s @ 24fps
                    {:frame-blob-key "b.png" :duration-frames 96}   ;; 4.0s @ 24fps
                    {:frame-blob-key "c.png" :duration-frames 96}]  ;; 4.0s @ 24fps
          transitions [{:duration-frames 48 :transition-type :dissolve}   ;; 2.0s
                       {:duration-frames 48 :transition-type :wipe}]      ;; 2.0s
          cmd (ffmpeg/xfade-chain-cmd segments transitions "out.mp4"
                                      {:width 320 :height 240 :fps 24})
          filter-str (str (second (drop-while #(not= "-filter_complex" %) cmd)))]
      (is (= "ffmpeg" (first cmd)))
      (is (= ["a.png" "b.png" "c.png"]
             (->> cmd (partition 2 1) (filter #(= "-i" (first %))) (map second))))
      (is (str/includes? filter-str "xfade=transition=fade"))
      (is (str/includes? filter-str "xfade=transition=wipeleft"))
      ;; stage 2 must consume stage 1's OUTPUT label ([vx1]) as an input --
      ;; not [v1] (which would silently discard the accumulated overlap).
      (is (re-find #"\[vx1\]\[v2\]xfade=" filter-str))
      (is (not (re-find #"\[v1\]\[v2\]xfade=" filter-str)))
      ;; stage 1 offset = D_0 - t1 = 4.0 - 2.0 = 2.0
      ;; stage 2 offset = D_1 - t2 = (4.0+4.0-2.0) - 2.0 = 4.0  (NOT 4.0-2.0=2.0,
      ;; the wrong answer a naive per-clip-duration loop would produce)
      (let [[_ off1] (re-find #"xfade=transition=fade:duration=([0-9.]+):offset=([0-9.]+)\[vx1\]" filter-str)
            offs (re-seq #"offset=([0-9.]+)" filter-str)]
        (is (= 2.0 #?(:clj (Double/parseDouble off1) :cljs (js/parseFloat off1))))
        (is (= [2.0 4.0] (mapv (fn [[_ o]] #?(:clj (Double/parseDouble o) :cljs (js/parseFloat o))) offs))))
      (is (some #(= "[v]" %) cmd))
      (is (some #(str/includes? (str %) "scale=320:240") cmd))))
  (testing "final output duration honors BOTH overlaps: sum(clip durations) - sum(transition durations)"
    ;; not asserted directly on the argv (ffmpeg computes that at render
    ;; time) -- this documents the arithmetic the offsets above encode:
    ;; 4.0+4.0+4.0 - 2.0-2.0 = 8.0s, matching kami.eizo.timeline's own
    ;; chained-overlap timeline-duration arithmetic.
    (is (= 8.0 (- (+ 4.0 4.0 4.0) 2.0 2.0))))
  (testing "degenerates correctly to a single stage for exactly 2 clips / 1 transition"
    (let [cmd (ffmpeg/xfade-chain-cmd
               [{:frame-blob-key "a.png" :duration-frames 96}
                {:frame-blob-key "b.png" :duration-frames 96}]
               [{:duration-frames 48 :transition-type :dissolve}]
               "out.mp4" {:width 320 :height 240 :fps 24})
          filter-str (str (second (drop-while #(not= "-filter_complex" %) cmd)))]
      (is (re-find #"\[v0\]\[v1\]xfade=transition=fade:duration=2.0:offset=2.0\[v\]" filter-str))))
  (testing "throws when segment/transition counts don't line up (need N-1 transitions for N segments)"
    (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
                 (ffmpeg/xfade-chain-cmd
                  [{:frame-blob-key "a.png" :duration-frames 96}
                   {:frame-blob-key "b.png" :duration-frames 96}
                   {:frame-blob-key "c.png" :duration-frames 96}]
                  [{:duration-frames 48 :transition-type :dissolve}]
                  "out.mp4" {:width 320 :height 240 :fps 24}))))
  (testing "throws when a later transition's duration exceeds the chain's accumulated duration so far"
    (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
                 (ffmpeg/xfade-chain-cmd
                  [{:frame-blob-key "a.png" :duration-frames 24}    ;; 1.0s
                   {:frame-blob-key "b.png" :duration-frames 24}    ;; 1.0s
                   {:frame-blob-key "c.png" :duration-frames 96}]   ;; 4.0s
                  [{:duration-frames 12 :transition-type :dissolve} ;; 0.5s, D_1 = 1.5s
                   {:duration-frames 48 :transition-type :wipe}]    ;; 2.0s > D_1 = 1.5s -- should throw
                  "out.mp4" {:width 320 :height 240 :fps 24}))))
  (testing "throws for an unknown transition-type in any stage before building any argv"
    (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
                 (ffmpeg/xfade-chain-cmd
                  [{:frame-blob-key "a.png" :duration-frames 96}
                   {:frame-blob-key "b.png" :duration-frames 96}
                   {:frame-blob-key "c.png" :duration-frames 96}]
                  [{:duration-frames 48 :transition-type :dissolve}
                   {:duration-frames 48 :transition-type :slide}]
                  "out.mp4" {:width 320 :height 240 :fps 24})))))
