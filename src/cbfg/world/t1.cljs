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
    :events [1 2 3 4]
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
            (map (fn [x] (dom/li nil x))
                 (:events app))))
   app-state
   {:target (. js/document (getElementById "events"))})
  (om/root render-world app-state
           {:target (. js/document (getElementById "world"))})
  (om/root render-world app-state
           {:target (. js/document (getElementById "world-map"))}))

(defn world-vis-init [el-prefix init-event-delay]
  (init-roots app-state))
