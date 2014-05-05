(ns cbfg.world.t1
  (:require-macros [cljs.core.async.macros :refer [go-loop]]
                   [cbfg.act :refer [act act-loop achan-buf aalts aput]])
  (:require [cljs.core.async :refer [chan]]
            [goog.dom :as gdom]
            [om.core :as om :include-macros true]
            [om.dom :as dom :include-macros true]
            [cbfg.vis :refer [listen-el get-el-value]]))

;; How to assign locations to world entities before rendering?

(def run-controls
  (atom {:controls ["play" "step" "pause"]
         :speed 1.0}))

(def run-history
  (atom {:snapshots {0 {}
                     10 {:a 1 :b 2 :c 3 :d 4}
                     20 {:a 11 :b 22 :c 33 :d 44}}
         :events [[0 :snapshot]
                  [1 :event [:a 1]]
                  [2 :event [:b 2]]
                  [3 :event [:c 3]]
                  [4 :event [:d 4]]
                  [10 :snapshot]
                  [11 :event [:a 11]]
                  [12 :event [:b 22]]
                  [13 :event [:c 33]]
                  [14 :event [:d 44]]
                  [20 :snapshot]
                  [21 :event [:a 21]]
                  [22 :event [:b 22]]

                  [0 :snapshot]
                  [1 :event [:a 1]]
                  [2 :event [:b 2]]
                  [3 :event [:c 3]]
                  [4 :event [:d 4]]
                  [10 :snapshot]
                  [11 :event [:a 11]]
                  [12 :event [:b 22]]
                  [13 :event [:c 33]]
                  [14 :event [:d 44]]
                  [20 :snapshot]
                  [21 :event [:a 21]]
                  [22 :event [:b 22]]]}))

(def run-world
  (atom {:a 10 :b 20 :c 30 :d 40}))

(def run-world-hover
  (atom nil))

; -------------------------------------------------------------------

(defn render-world [app owner]
  (apply dom/ul nil
         (map (fn [[k v]] (dom/li nil (str k ":" v))) app)))

(defn render-snapshot [app owner ss-ts]
  (render-world (get-in app [:snapshots ss-ts] nil) owner))

(defn on-event-focus [snapshot-ts event-ts]
  (when-let [ss (get-in @run-history [:snapshots snapshot-ts])]
    (reset! run-world-hover ss)))

(defn on-event-blur [snapshot-ts event-ts]
  (reset! run-world-hover nil))

(defn render-events [app owner]
  (apply dom/ul nil
         (second
          (reduce
           (fn [[last-snapshot-ts res] [ts kind args]]
             (if (= kind :snapshot)
               [ts
                (conj res
                      (dom/li #js {:className "snapshot"
                                   :onMouseEnter #(on-event-focus ts nil)
                                   :onMouseLeave #(on-event-blur ts nil)}
                              (str ts)))]
               [(or last-snapshot-ts ts)
                (conj res
                      (dom/li #js {:onMouseEnter
                                   #(on-event-focus last-snapshot-ts ts)
                                   :onMouseLeave
                                   #(on-event-blur last-snapshot-ts ts)}
                              (str ts (pr-str args))))]))
           [nil []]
           (:events app)))))

(defn render-controls[app owner]
     (apply dom/ul nil
            (map (fn [x] (dom/li nil (dom/button nil x)))
                 (:controls app))))
(defn init-roots []
  (om/root render-controls run-controls
           {:target (. js/document (getElementById "controls"))})
  (om/root render-events run-history
           {:target (. js/document (getElementById "events"))})
  (om/root render-world run-world
           {:target (. js/document (getElementById "world"))})
  (om/root render-world run-world
           {:target (. js/document (getElementById "world-map"))})
  (om/root render-world run-world-hover
           {:target (. js/document (getElementById "world-hover"))})
  (om/root render-world run-world-hover
           {:target (. js/document (getElementById "world-map-hover"))}))

(defn world-vis-init [el-prefix init-event-delay]
  (init-roots)
  (let [go-ch (listen-el (gdom/getElement "prog-go") "click")]
    (go-loop []
      (<! go-ch)
      (let [prog (get-el-value "prog")]
        (println :prog prog)
        (let [res (try (js/eval (str "with (cbfg) {" prog "}"))
                       (catch js/Object ex ex))]
          (println :res res)))
      (recur))))

