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

(defn dissoc-in [m [k & ks :as keys]]
  (if ks
    (if-let [next-map (get m k)]
      (let [new-map (dissoc-in next-map ks)]
        (if (seq new-map)
          (assoc m k new-map)
          (dissoc m k)))
      m)
    (dissoc m k)))

;; ------------------------------------------------

(defn vis-add-ch [vis ch]
  (update-in vis [:chs ch]
             (fn [v] (if (nil? v)
                       {:id ((:gen-id vis)) :msgs {} :first-taker-actx nil}
                       v))))

(def vis-event-handlers
  {"ago"
   {:beg (fn [vis actx args]
           (let [[child-actx] args]
             (swap! vis #(-> %
                             (assoc-in [:actxs child-actx]
                                       {:children {} :wait-chs {}})
                             (assoc-in [:actxs actx :children child-actx] true)))))
    :end (fn [vis actx args]
           (let [[child-actx result] args]
             (swap! vis #(-> %
                             (dissoc-in [:actxs child-actx])
                             (dissoc-in [:actxs actx :children child-actx])))))}
   "aclose"
   {:beg (fn [vis actx args]
           (let [[ch] args] nil))
    :end (fn [vis actx args]
           (let [[ch result] args] nil))}
   "atake"
   {:beg (fn [vis actx args]
           (let [[ch] args]
             (swap! vis #(-> %
                             (assoc-in [:actxs actx :wait-chs ch] :take)
                             (vis-add-ch ch)
                             (update-in [:chs ch :first-taker-actx]
                                        (fn [fta]
                                          (if fta fta actx)))))))
    :end (fn [vis actx args]
           (let [[ch msg] args]
             (swap! vis #(-> %
                             (dissoc-in [:actxs actx :wait-chs ch])
                             (dissoc-in [:chs ch :msgs msg])))
             (when (nil? msg) ; The ch is closed.
               (swap! vis #(dissoc-in % [:chs ch])))))}
   "aput"
   {:beg (fn [vis actx args]
           (let [[ch msg] args]
             (swap! vis #(-> %
                             (assoc-in [:actxs actx :wait-chs ch] :put)
                             (vis-add-ch ch)
                             (assoc-in [:chs ch :msgs msg] true)))))
    :end (fn [vis actx args]
           (let [[ch msg result] args]
             (swap! vis #(dissoc-in % [:actxs actx :wait-chs ch]))
             ; NOTE: Normally we should cleanup ch when nil result
             ; but looks like CLJS async returns wrong result from >!.
             ))}
   "aalts"
   {:beg (fn [vis actx args]
           (let [[ch-bindings] args
                 ; The actions will be [[ch :take] [ch :put] ...].
                 actions (map #(if (seq? %) [(first %) :put] [% :take])
                              ch-bindings)]
             (swap! vis #(update-in % [:actxs actx :wait-chs]
                                    (fn [wait-chs] ; Update wait-chs with actions.
                                      (reduce (fn [acc v]
                                                (assoc acc (first v) (second v)))
                                              wait-chs
                                              actions))))))
    :end (fn [vis actx args]
           (let [[ch-bindings result] args
                 chs (map #(if (seq? %) (first %) %) ch-bindings)
                 [result-msg result-ch] result]
             (doseq [ch chs]
               (swap! vis #(dissoc-in % [:actxs actx :wait-chs ch])))
             (swap! vis #(dissoc-in % [:chs result-ch :msgs result-msg]))
             (when (nil? result-msg) ; The ch is closed.
               (swap! vis #(dissoc-in % [:chs result-ch])))))}})

(defn vis-html-actx [vis actx]
  (if actx
    (let [d (get-in vis [:actxs actx])
          children (:children d)
          wait-chs (:wait-chs d)]
      ["<div>" (last actx)
       (if (not-empty wait-chs)
         [" -- waiting: ("
          (map (fn [kv]
                 (let [[ch wait-kind] kv]
                   [(:id (get (:chs vis) ch)) wait-kind ", "]))
               wait-chs)
          ")"]
         [])
       (if (not-empty children)
         ["<ul><li>children...<ul>"
          (map (fn [kv] ["<li>" (vis-html-actx vis (first kv)) "</li>"])
               children)
          "</ul></li></ul>"]
         [])
       "</div>"])
    "no actx"))

(defn vis-html-chs [vis]
  ["<div>channels...<ul>"
   (map (fn [ch-info]
          ["<li>"
           (:id ch-info) ": " (:msgs ch-info)
           (if-let [fta (:first-taker-actx ch-info)]
             [" --&gt; " (last fta)]
             "")
           "</li>"])
        (vals (:chs vis)))
   "</ul></div>"])

(defn vis-init [cmds cmd-handlers]
  (let [max-inflight (atom 10)
        event-delay (atom 0)
        event-ch (chan)
        last-id (atom 0)
        gen-id #(swap! last-id inc)
        w [{:gen-id gen-id
            :event-ch event-ch}]
        root-actx (atom nil)
        vis (atom {:actxs {} ; {actx -> {:children {child-actx -> true},
                             ;           :wait-chs {ch -> (:take|:put)}}}.
                   :chs {}   ; {ch -> {:id (gen-id),
                             ;         :msgs {msg -> true}
                             ;         :first-taker-actx actx-or-nil}}.
                   :gen-id gen-id})]
    (go-loop [num-events 0]
      (let [tdv @event-delay]
        (when (> tdv 0)
          (<! (timeout tdv))))
      (let [[actx event] (<! event-ch)
            [verb step & args] event
            vis-event-handler (get (get vis-event-handlers verb) step)]
        (vis-event-handler vis actx args)
        (set-el-innerHTML "vis-html"
                          (apply str
                                 (flatten [(vis-html-actx @vis @root-actx)
                                           "<hr/>"
                                           (vis-html-chs @vis)])))
        (set-el-innerHTML "vis"
                          (str "<circle cx='"
                               (mod num-events 500)
                               "' cy='100' r='10'"
                               " stroke='black' stroke-width='3'"
                               " fill='red'/>")))
      (recur (inc num-events)))
    (ago w-actx w
         (reset! root-actx w-actx)
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
