(ns cbfg.world.t1
  (:require-macros [cbfg.act :refer [act act-loop achan-buf aalts aput]])
  (:require [cljs.core.async :refer [chan]]
            [om.core :as om :include-macros true]
            [om.dom :as dom :include-macros true]))

(def app-state (atom {:text "hello"
                      :list ["Lion" "Zebra" "Buffalo" "Antelope"]}))

(defn vinit [el-id]
  (om/root
   (fn [app owner]
     (dom/h2 nil (:text app)))
   app-state
   {:target (. js/document (getElementById el-id))}))

(defn world-vis-init [el-prefix init-event-delay]
  (vinit el-prefix)
  (vinit "preview"))

