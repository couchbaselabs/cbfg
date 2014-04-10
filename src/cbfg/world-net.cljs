(ns cbfg.world-net
  (:require-macros [cbfg.ago :refer [ago ago-loop achan achan-buf aclose
                                     aalts aput atake atimeout]])
  (:require [cljs.core.async :refer [chan]]
            [cbfg.vis :refer [vis-init get-el-value set-el-innerHTML]]
            [cbfg.net :refer [make-net]]
            [cbfg.net-test]
            [cbfg.lane]
            [cbfg.world-lane]
            [cbfg.world-base :refer [replay-cmd-ch world-replay
                                     render-client-hist start-test]]))

(defn server-conn-loop [actx server-send-ch server-recv-ch close-server-recv-ch]
  (let [fenced-pump-lane-in-ch (achan actx)
        fenced-pump-lane-out-ch (achan actx)]
    (cbfg.lane/make-lane-pump actx
                              fenced-pump-lane-in-ch fenced-pump-lane-out-ch
                              cbfg.world-lane/make-fenced-pump-lane)
    (ago-loop fenced-pump-lane-in actx [num-ins 0]
              (let [msg (atake fenced-pump-lane-in server-recv-ch)]
                (aput fenced-pump-lane-in fenced-pump-lane-in-ch msg)
                (when (> (:sleep msg) 0)
                  (let [sleep-ch (atimeout fenced-pump-lane-in (:sleep msg))]
                    (atake fenced-pump-lane-in sleep-ch)))
                (recur (inc num-ins))))
    (ago-loop fenced-pump-lane-out actx [num-outs 0]
              (aput fenced-pump-lane-out server-send-ch
                    [(atake fenced-pump-lane-out fenced-pump-lane-out-ch)])
              (recur (inc num-outs)))))

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
                       cmd-rq ((get cbfg.world-lane/cmd-handlers (:op cmd)) cmd)]
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

(defn render-msg [msg class-extra]
  ["<div class='msg " class-extra "' style='color:" (:color msg) ";'>&#9679;"
   "<div class='result'>" (:result msg) "</div></div>"])

(defn render-msgs [curr-msgs prev-msgs]
  (loop [curr-msgs (reverse (seq curr-msgs))
         prev-msgs (reverse (seq prev-msgs))
         moving false
         out []]
    (let [[curr-deliver-at curr-msg] (first curr-msgs)
          [prev-deliver-at prev-msg] (first prev-msgs)]
      (cond
       (= nil curr-msg prev-msg) out
       (= (:opaque curr-msg) (:opaque prev-msg))
       (recur (rest curr-msgs)
              (rest prev-msgs)
              moving
              (conj out (render-msg curr-msg (when moving "msg-move"))))
       (nil? curr-msg)
       (recur nil
              (rest prev-msgs)
              moving
              (conj out (render-msg prev-msg "msg-exit")))
       :else
       (recur (rest curr-msgs)
              prev-msgs
              true
              (conj out (render-msg curr-msg "msg-enter")))))))

(defn render-net [vis net-actx-id output-el-id prev-addrs]
  (let [net-state (:loop-state (second (first (filter (fn [[actx actx-info]]
                                                        (= (last actx) net-actx-id))
                                                      (:actxs vis)))))
        addrs (atom {})
        coords (atom {})] ; Index positions.
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
          line-height 20
          addr-width 50
          addr-gap 200
          calc-xy (fn [addr] (let [c (get coords addr)]
                               [(* addr-width c (/ (+ addr-width addr-gap) addr-width))
                                (* line-height c)]))
          h ["<div style='height:" (apply max (map #(count (:outs %)) (vals @addrs))) "em;'>"
             (mapv (fn [[addr addr-v]]
                     (let [[addr-x addr-y] (calc-xy addr)]
                       ["<div class='addr' style='top:" addr-y "px; left:" addr-x "px;'>"
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
                                               from-y (+ addr-y (* line-height (get coords [addr from-port])))
                                               [to-addr-x to-addr-y] (calc-xy to-addr)
                                               to-x to-addr-x
                                               to-y (+ to-addr-y (* line-height (get coords [to-addr to-port])))
                                               dx (- from-x to-x)
                                               dy (- to-y from-y)
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
        cmd-ch (replay-cmd-ch cmd-inject-ch (keys cbfg.world-lane/cmd-handlers)
                              (fn [ev] {:op (.-id (.-target ev))
                                        :x (js/parseInt (get-el-value "x"))
                                        :y (js/parseInt (get-el-value "y"))
                                        :delay (js/parseInt (get-el-value "delay"))
                                        :fence (= (get-el-value "fence") "1")
                                        :lane (get-el-value "lane")
                                        :color (get-el-value "color")
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
