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
                                               :transition-duration-frames 48})))))
