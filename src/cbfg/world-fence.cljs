(ns cbfg.world-fence
  (:require-macros [cbfg.ago :refer [ago-loop achan-buf aalts aput]])
  (:require [cljs.core.async :refer [chan]]
            [cbfg.vis :refer [vis-init get-el-value]]
            [cbfg.fence :refer [make-fenced-pump]]
            [cbfg.fence-test]
            [cbfg.world-base :refer [replay-cmd-ch world-replay
                                     render-client-hist start-test]]))

(def cmd-handlers
  {"add"    (fn [c] (assoc c :rq #(cbfg.world-base/example-add % c)))
   "sub"    (fn [c] (assoc c :rq #(cbfg.world-base/example-sub % c)))
   "count"  (fn [c] (assoc c :rq #(cbfg.world-base/example-count % c)))
   "test"   (fn [c] (assoc c :rq #(cbfg.fence-test/test % (:opaque c))))})

(def max-inflight (atom 10))

(defn world-vis-init [el-prefix]
  (let [cmd-inject-ch (chan)
        cmd-ch (replay-cmd-ch cmd-inject-ch (keys cmd-handlers)
                              (fn [ev] {:op (.-id (.-target ev))
                                        :x (js/parseInt (get-el-value "x"))
                                        :y (js/parseInt (get-el-value "y"))
                                        :delay (js/parseInt (get-el-value "delay"))
                                        :fence (= (get-el-value "fence") "1")}))
        client-hist (atom {})] ; Keyed by opaque -> [request, replies].
    (vis-init (fn [world vis-chs]
                (let [in-ch (achan-buf world 100)
                      out-ch (achan-buf world 0)]
                  (ago-loop client world [num-ins 0 num-outs 0]
                            (let [[v ch] (aalts client [cmd-ch out-ch])
                                  ts (+ num-ins num-outs)]
                              (cond
                               (= ch cmd-ch) (if (= (:op v) "replay")
                                               (world-replay in-ch vis-chs world-vis-init el-prefix
                                                             @client-hist (:replay-to v))
                                               (let [cmd (assoc-in v [:opaque] ts)
                                                     cmd-rq ((get cmd-handlers (:op cmd)) cmd)]
                                                 (render-client-hist (swap! client-hist
                                                                            #(assoc % ts [cmd nil])))
                                                 (aput client in-ch cmd-rq)
                                                 (recur (inc num-ins) num-outs)))
                               (= ch out-ch) (do (render-client-hist (swap! client-hist
                                                                            #(update-in % [(:opaque v) 1]
                                                                                        conj [ts v])))
                                                 (recur num-ins (inc num-outs))))))
                  (make-fenced-pump world in-ch out-ch @max-inflight)))
              el-prefix nil)
    cmd-inject-ch))

(start-test "fence-test" cbfg.fence-test/test)
