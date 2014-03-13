(ns cbfg.core
  (:require-macros [cljs.core.async.macros :refer [go]])
  (:require cbfg.ddl
            [goog.dom :as dom]
            [goog.events :as events]
            [cljs.core.async :refer [<! put! chan]]))

(enable-console-print!)

(defn listen [el type]
  (let [out (chan)]
    (events/listen el type (fn [e] (put! out e)))
    out))

(let [clicks (listen (dom/getElement "go") "click")]
  (go (while true
        (println (<! clicks))
        (println (user-input)))))

(println (dom/getElement "input"))

(defn user-input []
  (.-value (dom/getElement "input")))

;; ------------------------------------------------

(def sim {:clock 0
          :init {}
          :model {}
          :choices []
          :past []
          :upcoming {}})

