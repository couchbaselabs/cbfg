;; Browser and UI-related stuff goes in this file.
;; generic stuff should go elsewhere.

(ns cbfg.core
  (:require-macros [cljs.core.async.macros :refer [go go-loop]]
                   [cbfg.ago :refer [ago atake]])
  (:require [clojure.string :as string]
            [cljs.core.async :refer [<! >! put! chan timeout]]
            [goog.dom :as gdom]
            [goog.events :as gevents]
            [om.core :as om :include-macros true]
            [om.dom :as dom :include-macros true]
            [sablono.core :as html :refer [html] :include-macros true]
            cbfg.ddl
            cbfg.fence))

(enable-console-print!)

(println cbfg.ddl/hi)

;; ------------------------------------------------

(defn get-el-value [elId]
  (.-value (gdom/getElement elId)))

(defn set-el-innerHTML [elId v]
  (set! (.-innerHTML (gdom/getElement elId)) v))

(defn listen [el type]
  (let [out (chan)]
    (gevents/listen el type (fn [e] (put! out e)))
    out))

;; ------------------------------------------------

(defn test-init [init-event-delay]
  (let [clicks (listen (gdom/getElement "go") "click")
        event-delay (atom init-event-delay)
        event-ch (chan)
        w [{:last-id (atom 0)
            :event-ch event-ch
            :chs (atom {}) :tot-chs (atom 0)}]]
    (go-loop [num-events 0]
      (let [tdv @event-delay]
        (when (> tdv 0)
          (<! (timeout tdv))))
      (let [event (<! event-ch)]
        (println num-events event)
        (set-el-innerHTML "event" [num-events event]))
      (recur (inc num-events)))
    (ago w-actx w
         (while true
           (.log js/console (<! clicks))
           (set-el-innerHTML "output" (get-el-value "input"))
           (let [res (string/join "\n" (atake w-actx (cbfg.fence/test w-actx)))]
             (set-el-innerHTML "output" (str "<pre>" res "</pre>"))
             (println "output" res))))
    event-delay))

(def test-event-delay (test-init 0))

(defn change-test-event-delay [d]
  (reset! test-event-delay d))

;; ------------------------------------------------

(def app-state
  (atom {:text "Hello world!"}))

(defn app-ui [app-state owner]
  (reify
    om/IRender
    (render [_]
      (dom/div nil
               (dom/h1 nil (:text app-state))))))

(om/root app-state
         app-ui
         (. js/document (getElementById "app")))
