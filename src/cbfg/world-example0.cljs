(ns cbfg.world-example0
  (:require-macros [cbfg.ago :refer [ago ago-loop achan-buf aput atake]])
  (:require [cljs.core.async :refer [<! timeout merge map<]]
            [goog.dom :as gdom]
            [cbfg.vis :refer [vis-init listen-el get-el-value set-el-innerHTML]]
            [cbfg.fence :refer [make-fenced-pump]]))

(defn example-add [actx x y delay]
  (ago example-add actx
       (let [timeout-ch (timeout delay)]
         (atake example-add timeout-ch)
         (+ x y))))

(defn example-sub [actx x y delay]
  (ago example-sub actx
       (let [timeout-ch (timeout delay)]
         (atake example-sub timeout-ch)
         (- x y))))

(def example-cmd-handlers
  {"add"  (fn [c] {:fence (:fence c) :rq #(example-add % (:x c) (:y c) (:delay c))})
   "sub"  (fn [c] {:fence (:fence c) :rq #(example-sub % (:x c) (:y c) (:delay c))})
   "test" (fn [c] {:fence (:fence c) :rq #(cbfg.fence/test %)})})

(def example-max-inflight (atom 10))

(defn example-init [el-prefix]
  (let [cmd-ch (map< (fn [ev] {:op (.-id (.-target ev))
                               :x (js/parseInt (get-el-value "x"))
                               :y (js/parseInt (get-el-value "y"))
                               :delay (js/parseInt (get-el-value "delay"))
                               :fence (= (get-el-value "fence") "1")})
                     (merge (map #(listen-el (gdom/getElement %) "click")
                                 (keys example-cmd-handlers))))]
    (vis-init (fn [world]
                (let [in-ch (achan-buf world 100)
                      out-ch (achan-buf world 0)]
                  (ago-loop user-in world [num-ins 0]
                            (let [cmd (<! cmd-ch)
                                  cmd-handler ((get example-cmd-handlers (:op cmd)) cmd)]
                              (aput user-in in-ch cmd-handler)
                              (recur (inc num-ins))))
                  (ago-loop user-out world [num-outs 0]
                            (let [result (atake user-out out-ch)]
                              (set-el-innerHTML (str el-prefix "-output") result)
                              (recur (inc num-outs))))
                  (make-fenced-pump world in-ch out-ch @example-max-inflight)))
              el-prefix)))

(example-init "vis")

