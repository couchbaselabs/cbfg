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
    :now 0
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

(defn vinit [el-id]
  (om/root
   (fn [app owner]
     (apply dom/ul nil
            (map (fn [text] (dom/li nil (dom/button nil text)))
                 (:controls app))))
   app-state
   {:target (. js/document (getElementById "controls"))})
  (om/root
   (fn [app owner]
     (dom/div nil (:text app)))
   app-state
   {:target (. js/document (getElementById el-id))}))

(defn world-vis-init [el-prefix init-event-delay]
  (vinit el-prefix)
  (vinit "world"))

