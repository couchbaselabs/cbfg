(ns cbfg.world-net
  (:require-macros [cbfg.ago :refer [ago ago-loop achan achan-buf aclose
                                     aalts aput atake atimeout]])
  (:require [cljs.core.async :refer [chan]]
            [cbfg.vis :refer [vis-init get-el-value set-el-innerHTML]]
            [cbfg.net :refer [make-net]]
            [cbfg.net-test]
            [cbfg.world-base :refer [replay-cmd-ch world-replay
                                     render-client-hist start-test]]))

(def cmd-handlers {"echo" identity})

(defn server-conn-loop [actx server-send-ch server-recv-ch close-server-recv-ch]
  (ago-loop server-conn-loop actx [num-requests 0]
            (if-let [msg (atake server-conn-loop server-recv-ch)]
              (do (when (> (:sleep msg) 0)
                    (let [sleep-ch (atimeout server-conn-loop (:sleep msg))]
                      (atake server-conn-loop sleep-ch)))
                  (aput server-conn-loop server-send-ch [msg])
                  (recur (inc num-requests)))
              (do (aclose server-conn-loop server-send-ch)
                  (aclose server-conn-loop close-server-recv-ch)))))

(defn server-accept-loop [actx accept-ch close-accept-ch]
  (ago-loop server-accept-loop actx [num-accepts 0]
            (if-let [[server-send-ch server-recv-ch close-server-recv-ch]
                     (atake server-accept-loop accept-ch)]
              (do (server-conn-loop server-accept-loop
                                    server-send-ch
                                    server-recv-ch close-server-recv-ch)
                  (recur (inc num-accepts)))
              (aclose server-accept-loop close-accept-ch))))

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

(defn render-msgs [curr-msgs prev-msgs]
  (loop [curr-msgs (reverse (seq curr-msgs))
         prev-msgs (reverse (seq prev-msgs))
         moving false
         out []]
    (let [[curr-deliver-at curr-msg] (first curr-msgs)
          [prev-deliver-at prev-msg] (first prev-msgs)]
      (cond
       (= nil curr-msg prev-msg)
       out
       (= (:opaque curr-msg) (:opaque prev-msg))
       (recur (rest curr-msgs)
              (rest prev-msgs)
              moving
              (conj out
                    ["<div class='msg"
                     (when moving " msg-move")
                     "' style='color:" (:msg curr-msg) ";'>&#9679;"
                     (:result curr-msg) "</div>"]))
       (nil? curr-msg)
       (recur nil
              (rest prev-msgs)
              moving
              (conj out
                    ["<div class='msg msg-exit' style='color:"
                     (:msg prev-msg) ";'>&#9679;"
                     (:result prev-msg) "</div>"]))
       :else
       (recur (rest curr-msgs)
              prev-msgs
              true
              (conj out
                    ["<div class='msg msg-enter' style='color:"
                     (:msg curr-msg) ";'>&#9679;"
                     (:result curr-msg) "</div>"]))))))

(defn render-net [vis net-actx-id output-el-id prev-addrs]
  (let [net-state (:loop-state (second (first (filter (fn [[actx actx-info]]
                                                        (= (last actx) net-actx-id))
                                                      (:actxs vis)))))
        addrs (atom {})
        coords (atom {})]
    (doseq [[[addr port] accept-chs] (:listens net-state)]
      (swap! addrs #(assoc-in % [addr :listens port] true)))
    (doseq [[send-ch stream] (:streams net-state)]
      (if (= (:side stream) :client)
        (swap! addrs #(assoc-in % [(:client-addr stream) :outs
                                   [(:accept-addr stream) (:accept-port stream)]
                                   [(:client-port stream)
                                    (:server-addr stream) (:server-port stream)]] (:msgs stream)))
        (swap! addrs #(assoc-in % [(:server-addr stream) :outs
                                   [(:accept-addr stream) (:accept-port stream)]
                                   [(:server-port stream)
                                    (:client-addr stream) (:client-port stream)]] (:msgs stream)))))
    (doall (map-indexed
            (fn [addr-idx [addr addr-v]]
              (swap! coords #(assoc % addr addr-idx))
              (doseq [[[accept-addr accept-port] accept-addr-port-v] (sort-by first (:outs addr-v))]
                (doall (map-indexed
                        (fn [idx [[from-port to-addr to-port] msgs]]
                          (swap! coords #(assoc % [addr from-port] idx)))
                        (sort-by first accept-addr-port-v)))))
            (sort-by first @addrs)))
    (let [coords @coords
          addr-width 50
          addr-gap 200
          calc-x (fn [addr] (* addr-width
                               (/ (+ addr-width addr-gap)
                                  addr-width)
                               (get coords addr)))
          h ["<div style='height:" (apply max (map #(count (:outs %)) (vals @addrs))) "em;'>"
             (mapv (fn [[addr addr-v]]
                     (let [addr-x (calc-x addr)]
                       ["<div class='addr' style='left:" addr-x "px;'>"
                        "<label>" addr "</label>"
                        (mapv (fn [[[accept-addr accept-port] accept-addr-port-v]]
                                ["<div class='accept-addr-port'>"
                                 " <span class='accept-addr'>" accept-addr "</span>"
                                 " <span class='accept-port'>" accept-port "</span>"
                                 (when (and (= accept-addr addr)
                                            (get (:listens addr-v) accept-port))
                                   ["<div class='listen'>" accept-port "</div>"])
                                 (mapv (fn [[[from-port to-addr to-port] msgs]]
                                         (let [from-x addr-x
                                               from-y (get coords [addr from-port])
                                               to-x (calc-x to-addr)
                                               to-y (get coords [to-addr to-port])
                                               dx (- from-x to-x)
                                               dy (- from-y to-y)
                                               rad (Math/atan2 dx dy)
                                               dist (Math/abs (Math/sqrt (+ (* dx dx) (* dy dy))))
                                               prev-msgs (get-in @prev-addrs [addr :outs
                                                                              [accept-addr accept-port]
                                                                              [from-port to-addr to-port]])]
                                           ["<div class='port'>" from-port " --&gt; " to-addr ":" to-port
                                            " <div class='msgs"
                                            (when (= accept-addr addr)
                                              " to-client")
                                            "' style='min-height:" (- dist 60) "px;"
                                            " transform-origin:top left;"
                                            " -ms-transform-origin:top left;"
                                            " -webkit-transform-origin:top left;"
                                            " transform:rotate(" rad "rad);"
                                            " -ms-transform:rotate(" rad "rad);"
                                            " -webkit-transform:rotate(" rad "rad);'>"
                                            (render-msgs msgs prev-msgs)
                                            " </div>"
                                            "</div>"]))
                                       (sort-by first accept-addr-port-v))
                                 "</div>"])
                              (sort-by first (:outs addr-v)))
                        "</div>"]))
                  (sort-by first @addrs))
             "</div>"]]
      (when (not= @prev-addrs @addrs)
        (reset! prev-addrs @addrs)
        (set-el-innerHTML output-el-id
                          (apply str (flatten h)))))))

(defn world-vis-init [el-prefix init-event-delay]
  (let [cmd-inject-ch (chan)
        cmd-ch (replay-cmd-ch cmd-inject-ch (keys cmd-handlers)
                              (fn [ev] {:op (.-id (.-target ev))
                                        :msg (get-el-value "msg")
                                        :sleep (js/parseInt (get-el-value "sleep"))}))
        client-hist (atom {}) ; Keyed by opaque -> [request, replies].
        render-state (atom {})]
    (vis-init (fn [world vis-chs]
                (let [connect-ch (achan-buf world 10)
                      listen-ch (achan-buf world 10)
                      listen-result-ch (achan world)]
                  (make-net world listen-ch connect-ch)
                  (ago init-world world
                       (aput init-world listen-ch [:server 8000 listen-result-ch])
                       (when-let [[accept-ch close-accept-ch] (atake init-world listen-result-ch)]
                         (atake init-world listen-result-ch)
                         (server-accept-loop world accept-ch close-accept-ch)
                         (ago client-init world
                              (let [connect-result-ch (achan client-init)]
                                (aput client-init connect-ch [:server 8000 :client connect-result-ch])
                                (when-let [[client-send-ch client-recv-ch close-client-recv-ch]
                                           (atake client-init connect-result-ch)]
                                  (atake client-init connect-result-ch)
                                  (client-loop world cmd-ch
                                               client-hist
                                               client-send-ch client-recv-ch
                                               vis-chs world-vis-init el-prefix))))))))
              el-prefix
              #(render-net % "net-1" "net" render-state)
              init-event-delay)
    cmd-inject-ch))

(start-test "net-test" cbfg.net-test/test)
