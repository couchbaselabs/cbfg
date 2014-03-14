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

(defn get-el-value [elId]
  (.-value (dom/getElement elId)))

(defn set-el-innerHTML [elId v]
  (set! (.-innerHTML (dom/getElement elId)) v))

(defn listen [el type]
  (let [out (chan)]
    (events/listen el type (fn [e] (put! out e)))
    out))

(defn init [init-tick-delay]
  (let [clicks (listen (dom/getElement "go") "click")
        tick-delay (atom init-tick-delay)
        tick-ch (chan)
        w [{:tick-ch tick-ch :tot-ticks 0
            :chs (atom {}) :tot-chs 0}]]
    (go-loop [t 0]
      (let [tdv @tick-delay]
        (when (> tdv 0)
          (<! (timeout tdv))))
      (>! tick-ch t)
      (set-el-innerHTML "tot-ticks" t)
      (recur (inc t)))
    (ago w-actx w
         (while true
           (.log js/console (<! clicks))
           (set-el-innerHTML "output" (get-el-value "input"))
           (let [res (string/join "\n" (atake w-actx (cbfg.fence/test w-actx)))]
             (set-el-innerHTML "output" (str "<pre>" res "</pre>"))
             (println res))))
    tick-delay))

(def tick-delay (init 0))

(defn change-tick-delay [d]
  (reset! tick-delay d))

;; ------------------------------------------------

(def sim {:clock 0
          :init {}
          :model {}
          :choices []
          :past []
          :upcoming {}})

