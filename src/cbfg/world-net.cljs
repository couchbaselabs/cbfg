(ns cbfg.world-net
  (:require-macros [cbfg.ago :refer [ago ago-loop achan achan-buf aalts aput atake]])
  (:require [cljs.core.async :refer [chan]]
            [cbfg.vis :refer [vis-init get-el-value]]
            [cbfg.net :refer [make-net]]
            [cbfg.net-test]
            [cbfg.world-base :refer [replay-cmd-ch world-replay
                                     render-client-hist start-test]]))

(def cmd-handlers
  {"echo" (fn [c] (assoc c :rq #(cbfg.world-base/example-add % c)))
   "test" (fn [c] (assoc c :rq #(cbfg.net-test/test % (:opaque c))))})

(def max-inflight (atom 10))

(defn world-vis-init [el-prefix init-event-delay]
  (let [cmd-inject-ch (chan)
        cmd-ch (replay-cmd-ch cmd-inject-ch (keys cmd-handlers)
                              (fn [ev] {:op (.-id (.-target ev))
                                        :msg (get-el-value "msg")}))
        client-hist (atom {})] ; Keyed by opaque -> [request, replies].
    (vis-init (fn [world vis-chs]
                (let [in-ch (achan-buf world 100)
                      out-ch (achan-buf world 0)
                      listen-ch (achan-buf world 10)
                      connect-ch (achan-buf world 10)]
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
                               (= ch out-ch) (when v
                                               (do (render-client-hist (swap! client-hist
                                                                              #(update-in % [(:opaque v) 1]
                                                                                          conj [ts v])))
                                                   (recur num-ins (inc num-outs)))))))
                  (ago server-init world
                       (let [listen-result-ch (achan server-init)]
                         (aput server-init listen-ch [:server 8000 listen-result-ch])
                         (let [[close-accept-ch accept-ch] (atake server-init listen-result-ch)]
                           (ago-loop server-accept-loop world
                                     [num-accepts 0]
                                     (let [[server-send-ch server-recv-ch close-server-recv-ch]
                                           (atake server-accept-loop accept-ch)]
                                       (ago-loop server-conn-loop server-accept-loop
                                                 [num-requests 0]
                                                 (aput server-conn-loop server-send-ch
                                                       (atake server-conn-loop server-recv-ch))
                                                 (recur (inc num-requests)))
                                       (recur (inc num-accepts)))))))
                  (make-net world listen-ch connect-ch)))
              el-prefix nil init-event-delay)
    cmd-inject-ch))

(start-test "net-test" cbfg.net-test/test)
