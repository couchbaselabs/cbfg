;; Browser and UI-related stuff goes in this file.
;; generic stuff should go elsewhere.

(ns cbfg.core
  (:require-macros [cljs.core.async.macros :refer [go go-loop]]
                   [cbfg.ago :refer [ago ago-loop achan-buf aput atake]])
  (:require [clojure.string :as string]
            [cljs.core.async :refer [<! >! put! chan timeout merge map<]]
            [goog.dom :as gdom]
            [goog.events :as gevents]
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

(def vis-event-handlers
  {"ago"
   {:beg (fn [vis actx args]
           (let [[child-actx] args] 1))
    :end (fn [vis actx args]
           (let [[child-actx result] args] 2))}
   "aclose"
   {:beg (fn [vis actx args]
           (let [[ch] args] 1))
    :end (fn [vis actx args]
           (let [[ch result] args] 2))}
   "atake"
   {:beg (fn [vis actx args]
           (let [[ch] args] 1))
    :end (fn [vis actx args]
           (let [[ch result] args] 2))}
   "aput"
   {:beg (fn [vis actx args]
           (let [[ch msg] args] 1))
    :end (fn [vis actx args]
           (let [[ch msg result] args] 2))}
   "aalts"
   {:beg (fn [vis actx args]
           (let [[chs] args] 1))
    :end (fn [vis actx args]
           (let [[chs result] args] 2))}})

(defn vis-init [cmds cmd-handlers]
  (let [vis (atom {:actxs {} :chs {}})
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
      (let [[actx event] (<! event-ch)
            [verb step & args] event
            vis-event-handler (get (get vis-event-handlers verb) step)]
        (println num-events event (vis-event-handler vis actx args) args)
        (set-el-innerHTML "event" [num-events event])
        (set-el-innerHTML "vis"
                          (str "<circle cx='"
                               (mod num-events 500)
                               "' cy='100' r='10'"
                               " stroke='black' stroke-width='3'"
                               " fill='red'/>")))
      (recur (inc num-events)))
    (ago w-actx w
         (let [in (achan-buf w-actx 100)
               out (achan-buf w-actx 1)
               fdp (make-fenced-pump w-actx in out @max-inflight)
               gch (ago-loop main-out w-actx [acc nil]
                             (let [result (atake main-out out)]
                               (set-el-innerHTML "output" result)
                               (recur (conj acc result))))]
           (ago-loop main-in w-actx [num-cmds 0]
                     (let [cmd (<! cmds)
                           cmd-handler ((get cmd-handlers (:op cmd)) cmd)]
                       (aput main-in in cmd-handler)
                       (recur (inc num-cmds))))))))

;; ------------------------------------------------

(defn example-add [actx x y delay]
  (ago example-add actx
       (<! (timeout delay))
       (+ x y)))

(defn example-sub [actx x y delay]
  (ago example-sub actx
       (<! (timeout delay))
       (- x y)))

(def example-cmd-handlers
  {"add"
   (fn [cmd] {:rq #(example-add % (:x cmd) (:y cmd) (:delay cmd))
              :fence (:fence cmd)})
   "sub"
   (fn [cmd] {:rq #(example-sub % (:x cmd) (:y cmd) (:delay cmd))
              :fence (:fence cmd)})
   "test"
   (fn [cmd] {:rq #(cbfg.fence/test %)
              :fence (:fence cmd)})})

(vis-init (map< (fn [id] {:op (.-id (.-target id))
                          :x (js/parseInt (get-el-value "x"))
                          :y (js/parseInt (get-el-value "y"))
                          :delay (js/parseInt (get-el-value "delay"))
                          :fence (= (get-el-value "fence") "1")})
                (merge (map #(listen (gdom/getElement %) "click")
                            (keys example-cmd-handlers))))
          example-cmd-handlers)
