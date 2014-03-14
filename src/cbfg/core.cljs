;; Browser and UI-related stuff goes in this file.
;; generic stuff should go elsewhere.

(ns cbfg.core
  (:require-macros [cljs.core.async.macros :refer [go go-loop]]
                   [cbfg.ago :refer [ago atake]])
  (:require [clojure.string :as string]
            [cljs.core.async :refer [<! >! put! chan timeout]]
            [goog.dom :as dom]
            [goog.events :as events]
            cbfg.ddl
            cbfg.fence))

(enable-console-print!)

(println cbfg.ddl/hi)

(defn user-input []
  (.-value (dom/getElement "input")))

(defn listen [el type]
  (let [out (chan)]
    (events/listen el type (fn [e] (put! out e)))
    out))

(defn init []
  (let [clicks (listen (dom/getElement "go") "click")
        tick-delay (atom 1000)
        tick-ch (chan)
        w [{:tick-ch tick-ch :tot-ticks 0
            :chs (atom {}) :tot-chs 0}]]
    (go-loop [t 0]
      (<! (timeout @tick-delay))
      (>! tick-ch t)
      (recur (inc t)))
    (ago world w
         (while true
           (<! clicks)
           (set! (.-innerHTML (dom/getElement "output"))
                 (user-input))
           (println (string/join "\n" (atake world (cbfg.fence/test world))))))
    tick-delay))

(def tick-delay (init))

(defn change-tick-delay [d]
  (reset! tick-delay d))

;; ------------------------------------------------

(def sim {:clock 0
          :init {}
          :model {}
          :choices []
          :past []
          :upcoming {}})

