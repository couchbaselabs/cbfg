(ns cbfg.world-net
  (:require-macros [cbfg.ago :refer [ago ago-loop achan achan-buf aalts aput atake]])
  (:require [cljs.core.async :refer [chan]]
            [cbfg.vis :refer [vis-init get-el-value]]
            [cbfg.net :refer [make-net]]
            [cbfg.net-test]
            [cbfg.world-base :refer [replay-cmd-ch world-replay
                                     render-client-hist start-test]]))

(def cmd-handlers
  {"echo" (fn [c] c)})

(defn server-conn-loop [actx server-send-ch server-recv-ch]
  (ago-loop server-conn-loop actx [num-requests 0]
            (let [msg (atake server-conn-loop server-recv-ch)]
              (aput server-conn-loop server-send-ch [msg])
              (recur (inc num-requests)))))

(defn server-accept-loop [actx accept-ch]
  (ago-loop server-accept-loop actx [num-accepts 0]
            (let [a (atake server-accept-loop accept-ch)]
              (when a
                (let [[server-send-ch server-recv-ch close-server-recv-ch] a]
                  (server-conn-loop server-accept-loop
                                    server-send-ch
                                    server-recv-ch)
                (recur (inc num-accepts)))))))

(defn client-loop [actx cmd-ch client-hist client-send-ch client-recv-ch vis-chs world-vis-init el-prefix]
  (ago-loop client-loop actx [num-requests 0 num-responses 0]
            (let [[v ch] (aalts client-loop [cmd-ch client-recv-ch])
                  ts (+ num-requests num-responses)]
              (cond
               (= ch cmd-ch)
               (if (= (:op v) "replay")
                 (world-replay client-send-ch vis-chs world-vis-init el-prefix
                               @client-hist (:replay-to v))
                 (let [cmd (assoc-in v [:opaque] ts)
                       cmd-rq ((get cmd-handlers (:op cmd)) cmd)]
                   (render-client-hist (swap! client-hist
                                              #(assoc % ts [cmd nil])))
                   (aput client-loop client-send-ch [cmd-rq])
                   (recur (inc num-requests) num-responses)))
               (= ch client-recv-ch)
               (when v
                 (render-client-hist (swap! client-hist
                                            #(update-in % [(:opaque v) 1]
                                                        conj [ts v])))
                 (recur num-requests (inc num-responses)))))))

(defn world-vis-init [el-prefix init-event-delay]
  (let [cmd-inject-ch (chan)
        cmd-ch (replay-cmd-ch cmd-inject-ch (keys cmd-handlers)
                              (fn [ev] {:op (.-id (.-target ev))
                                        :msg (get-el-value "msg")}))
        client-hist (atom {})] ; Keyed by opaque -> [request, replies].
    (vis-init (fn [world vis-chs]
                (let [connect-ch (achan-buf world 10)
                      listen-ch (achan-buf world 10)
                      listen-result-ch (achan world)]
                  (make-net world listen-ch connect-ch)
                  (ago init-world world
                       (aput init-world listen-ch [:server 8000 listen-result-ch])
                       (when-let [[accept-ch close-accept-ch] (atake init-world listen-result-ch)]
                         (server-accept-loop world accept-ch)
                         (ago client-init world
                              (let [connect-result-ch (achan client-init)]
                                (aput client-init connect-ch [:server 8000 :client connect-result-ch])
                                (when-let [[client-send-ch client-recv-ch close-client-recv-ch]
                                           (atake client-init connect-result-ch)]
                                  (client-loop world cmd-ch
                                               client-hist
                                               client-send-ch client-recv-ch
                                               vis-chs world-vis-init el-prefix))))))))
              el-prefix nil init-event-delay)
    cmd-inject-ch))

(start-test "net-test" cbfg.net-test/test)
