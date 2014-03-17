;; Browser and UI-related stuff goes in this file.
;; generic stuff should go elsewhere.

(ns cbfg.core
  (:require-macros [cljs.core.async.macros :refer [go go-loop]]
                   [cbfg.ago :refer [ago ago-loop achan-buf aput atake]])
  (:require [clojure.string :as string]
            [cljs.core.async :refer [<! >! put! chan timeout merge]]
            [goog.dom :as gdom]
            [goog.events :as gevents]
            [om.core :as om :include-macros true]
            [om.dom :as dom :include-macros true]
            [sablono.core :as html :refer [html] :include-macros true]
            cbfg.ddl
            [cbfg.fence :refer [make-fenced-pump]]))

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
  (let [clicks (listen (gdom/getElement "test") "click")
        event-delay (atom init-event-delay)
        event-ch (chan)
        last-id (atom 0)
        w [{:gen-id #(swap! last-id inc)
            :event-ch event-ch}]]
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

(def v-event-handlers
  {"ago"
   {:beg (fn [v actx args]
           (let [[child-actx] args] 1))
    :end (fn [v actx args]
           (let [[child-actx result] args] 2))}
   "aclose"
   {:beg (fn [v actx args]
           (let [[ch] args] 1))
    :end (fn [v actx args]
           (let [[ch result] args] 2))}
   "atake"
   {:beg (fn [v actx args]
           (let [[ch] args] 1))
    :end (fn [v actx args]
           (let [[ch result] args] 2))}
   "aput"
   {:beg (fn [v actx args]
           (let [[ch msg] args] 1))
    :end (fn [v actx args]
           (let [[ch msg result] args] 2))}
   "aalts"
   {:beg (fn [v actx args]
           (let [[chs] args] 1))
    :end (fn [v actx args]
           (let [[chs result] args] 2))}})

;; ------------------------------------------------

(defn example-add [actx x y delay]
  (ago example-add actx
       (<! (timeout delay))
       (+ x y)))

(defn example-sub [actx x y delay]
  (ago example-sub actx
       (<! (timeout delay))
       (- x y)))

(def cmd-handlers {"add" (fn []
                           (let [x (js/parseInt (get-el-value "x"))
                                 y (js/parseInt (get-el-value "y"))
                                 fence (get-el-value "fence")
                                 delay (js/parseInt (get-el-value "delay"))]
                             {:rq #(example-add % x y delay) :fence (= fence "1")})),
                   "sub" (fn []
                           (let [x (js/parseInt (get-el-value "x"))
                                 y (js/parseInt (get-el-value "y"))
                                 fence (get-el-value "fence")
                                 delay (js/parseInt (get-el-value "delay"))]
                             {:rq #(example-sub % x y delay) :fence (= fence "1")}))})

(defn vis-init []
  (let [cmds (merge [(listen (gdom/getElement "add") "click")
                     (listen (gdom/getElement "sub") "click")])
        max-inflight (atom 10)
        event-delay (atom 0)
        event-ch (chan)
        last-id (atom 0)
        w [{:gen-id #(swap! last-id inc)
            :event-ch event-ch}]]
    (go-loop [num-events 0]
      (let [tdv @event-delay]
        (when (> tdv 0)
          (<! (timeout tdv))))
      (let [[actx event] (<! event-ch)]
        (println num-events event)
        (set-el-innerHTML "event" [num-events event])
        (set-el-innerHTML "vis"
                          (str "<circle cx='"
                               (mod num-events 500)
                               "' cy='100' r='10' stroke='black' stroke-width='3' fill='red'/>")))
      (recur (inc num-events)))
    (ago w-actx w
         (let [in (achan-buf w-actx 100)
               out (achan-buf w-actx 1)
               fdp (make-fenced-pump w-actx in out @max-inflight)
               gch (ago-loop main-out w-actx [acc nil]
                             (let [result (atake main-out out)]
                               (set-el-innerHTML "output" (str "<pre>" result "</pre>"))
                               (recur (conj acc result))))]
           (ago-loop main-in w-actx []
                     (let [cmd (.-id (.-target (<! cmds)))
                           cmd-handler ((get cmd-handlers cmd))]
                       (aput main-in in cmd-handler)
                       (recur)))))))

(vis-init)
