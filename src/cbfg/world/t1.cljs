(ns cbfg.world.t1
  (:require-macros [cbfg.act :refer [act act-loop achan-buf aalts aput]])
  (:require [cljs.core.async :refer [chan]]
            [om.core :as om :include-macros true]
            [om.dom :as dom :include-macros true]))

;; How to assign locations to world entities before rendering?

(def app-state
  (atom
   {:text "hello"
    :controls ["play" "step" "pause"]
    :speed 1.0
    :snapshots {0 {}
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
             [22 :event [:b 22]]]
    :world {:a 11 :b 22 :c 33}
    :hover nil}))

(def app-history
  (atom []))

(def app-future
  (atom []))

(def transient-state
  (atom nil))

(def preview-state
  (atom {:focus nil}))

(def ghost-state
  (atom nil))

(defn render-world [app owner]
  (apply dom/ul nil
         (map (fn [[k v]] (dom/li nil (str k ":" v)))
              (:world app))))

(defn render-snapshot [app owner ss-ts]
  (apply dom/ul nil
         (map (fn [[k v]] (dom/li nil (str k "=" v)))
              (get-in app [:snapshots ss-ts] nil))))

(defn event-focus [snapshot-ts event-ts]
  (println :event-focus snapshot-ts event-ts))
(defn event-blur [snapshot-ts event-ts]
  (println :event-blur snapshot-ts event-ts))

(defn init-roots [app-state]
  (om/root
   (fn [app owner]
     (apply dom/ul nil
            (map (fn [x] (dom/li nil (dom/button nil x)))
                 (:controls app))))
   app-state
   {:target (. js/document (getElementById "controls"))})

  (om/root
   (fn [app owner]
     (apply dom/ul nil
            (second
             (reduce
              (fn [[last-snapshot-ts res] [ts kind args]]
                (if (= kind :snapshot)
                  [ts
                   (conj res
                         (dom/li #js {:onMouseEnter #(event-focus ts nil)
                                      :onMouseLeave #(event-blur ts nil)}
                                 (str ts)
                                 (render-snapshot app owner ts)))]
                  [last-snapshot-ts
                   (conj res
                         (dom/li #js {:onMouseEnter
                                      #(event-focus last-snapshot-ts ts)
                                      :onMouseLeave
                                      #(event-blur last-snapshot-ts ts)}
                                 (str ts (pr-str args))))]))
              [nil []]
              (:events app)))))
   app-state
   {:target (. js/document (getElementById "events"))})

  (om/root render-world app-state
           {:target (. js/document (getElementById "world"))})
  (om/root render-world app-state
           {:target (. js/document (getElementById "world-map"))}))

(defn world-vis-init [el-prefix init-event-delay]
  (init-roots app-state))
