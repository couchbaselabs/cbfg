(ns cbfg.world.net
  (:require-macros [cbfg.act :refer [act act-loop achan achan-buf aclose
                                     aput atake atimeout]])
  (:require [cljs.core.async :refer [chan]]
            [cbfg.vis :refer [vis-init get-el-value set-el-innerHTML]]
            [cbfg.net :refer [make-net]]
            [cbfg.test.net]
            [cbfg.lane]
            [cbfg.world.lane]
            [cbfg.world.base :refer [replay-cmd-ch world-cmd-loop start-test]]))

(defn server-conn-loop [actx server-send-ch server-recv-ch close-server-recv-ch]
  (let [fenced-pump-lane-in-ch (achan actx)
        fenced-pump-lane-out-ch (achan actx)]
    (cbfg.lane/make-lane-pump actx
                              fenced-pump-lane-in-ch fenced-pump-lane-out-ch
                              cbfg.world.lane/make-fenced-pump-lane)
    (act-loop fenced-pump-lane-in actx [num-ins 0]
              (let [msg (atake fenced-pump-lane-in server-recv-ch)]
                (println :fenced-pump-lane-in msg)
                (aput fenced-pump-lane-in fenced-pump-lane-in-ch msg)
                (when (> (:sleep msg) 0)
                  (let [sleep-ch (atimeout fenced-pump-lane-in (:sleep msg))]
                    (atake fenced-pump-lane-in sleep-ch)))
                (recur (inc num-ins))))
    (act-loop fenced-pump-lane-out actx [num-outs 0]
              (aput fenced-pump-lane-out server-send-ch
                    [(atake fenced-pump-lane-out fenced-pump-lane-out-ch)])
              (recur (inc num-outs)))))

(defn server-accept-loop [actx accept-ch close-accept-ch]
  (act-loop server-accept-loop actx [num-accepts 0]
            (if-let [[server-send-ch server-recv-ch close-server-recv-ch]
                     (atake server-accept-loop accept-ch)]
              (do (server-conn-loop server-accept-loop
                                    server-send-ch
                                    server-recv-ch close-server-recv-ch)
                  (recur (inc num-accepts)))
              (aclose server-accept-loop close-accept-ch))))

(defn client-loop [actx connect-ch server-addr server-port client-addr res-ch
                   & {:keys [start-cb]}]
  (let [cmd-ch (achan actx)]
    (act client-loop actx
         (when start-cb (start-cb))
         (let [connect-result-ch (achan client-loop)]
           (aput client-loop connect-ch [server-addr server-port
                                         client-addr connect-result-ch])
           (when-let [[client-send-ch client-recv-ch close-client-recv-ch]
                      (atake client-loop connect-result-ch)]
             (act-loop client-loop-in actx [num-ins 0]
                       (when-let [msg (atake client-loop-in cmd-ch)]
                         (println :client-loop-in-msg msg)
                         (aput client-loop-in client-send-ch [msg])
                         (recur (inc num-ins))))
             (act-loop client-loop-out actx [num-outs 0]
                       (when-let [msg (atake client-loop-out client-recv-ch)]
                         (aput client-loop-out res-ch msg)
                         (recur (inc num-outs)))))))
    cmd-ch))

(defn calc-addrs [net-state]
  (let [addrs (atom {})]
    (doseq [[[addr port] accept-chs] (:listens net-state)]
      (swap! addrs #(assoc-in % [addr :listens port] true)))
    (doseq [[send-ch stream] (:streams net-state)]
      (if (= (:side stream) :client)
        (swap! addrs #(assoc-in % [(:client-addr stream) :outs
                                   [(:accept-addr stream) (:accept-port stream)]
                                   [(:client-port stream)
                                    (:server-addr stream) (:server-port stream)]]
                                (:msgs stream)))
        (swap! addrs #(assoc-in % [(:server-addr stream) :outs
                                   [(:accept-addr stream) (:accept-port stream)]
                                   [(:server-port stream)
                                    (:client-addr stream) (:client-port stream)]]
                                (:msgs stream)))))
    @addrs))

(defn calc-coords [addrs]
  (let [coords (atom {}) ; Index positions.
        naddrs (count addrs) ; Assign coords.
        conns (sort (mapcat (fn [[addr addr-v]]
                              (mapcat (fn [[[accept-addr accept-port]
                                            accept-addr-port-v]]
                                        (map (fn [[[from-port to-addr to-port] msgs]]
                                               [(not= addr accept-addr)
                                                addr accept-addr accept-port
                                                from-port])
                                             accept-addr-port-v))
                                      (:outs addr-v)))
                            addrs))]
    (doall (map-indexed (fn [addr-idx [addr addr-v]]
                          (swap! coords
                                 #(assoc % addr
                                         [addr-idx (rem (* (dec naddrs) addr-idx)
                                                        naddrs)])))
                        (sort-by first addrs)))
    (reduce (fn [prev [is-client addr accept-addr accept-port port]]
              (let [idx (if (= (first prev) addr) (second prev) 0)]
                (swap! coords #(assoc % [addr port] idx))
                [addr (inc idx)]))
            nil conns)
    @coords))

(defn render-msg [msg class-extra style-extra]
  ["<div class='msg " class-extra
   "' style='color:" (:color msg) "; " style-extra "'>&#9679;"
   "<div class='result'>" (:result msg) "</div></div>"])

(defn render-msgs [curr-msgs prev-msgs dist line-height geom]
  (let [msg-top-max (get geom :msg-top-max 40)
        msg-top-exit (get geom :msg-top-exit 70)]
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
                (conj out (render-msg curr-msg (when moving "msg-move") "")))
         (nil? curr-msg)
         (recur nil
                (rest prev-msgs)
                moving
                (conj out
                      (render-msg prev-msg "msg-exit"
                                  ["top:" (max msg-top-max
                                               (- dist
                                                  (* line-height (count out))
                                                  msg-top-exit))
                                   "px;"])))
         :else
         (recur (rest curr-msgs)
                prev-msgs
                true
                (conj out (render-msg curr-msg "msg-enter" ""))))))))

(defn render-net-addrs-html [addrs prev-addrs coords addr-override-xy addr-attrs-fn geom]
  (let [top-height (get geom :top-height 60)
        line-height (get geom :line-height 20)
        msgs-height-sub (get geom :msgs-height-sub 60)
        addr-width (get geom :addr-width 50)
        addr-gap (get geom :addr-gap 80)
        calc-xy (fn [addr] (or (and addr-override-xy (addr-override-xy addr))
                               (let [[c0 c1] (get coords addr)]
                                 [(* addr-width c0 (/ (+ addr-width addr-gap) addr-width))
                                  (* top-height c1)])))
        h ["<div style='height:" (apply max (map #(count (:outs %))
                                                 (vals addrs))) "em;'>"
           (mapv
            (fn [[addr addr-v]]
              (let [[addr-x addr-y] (calc-xy addr)
                    addr-attrs (or (and addr-attrs-fn (addr-attrs-fn addr)) "")]
                ["<div class='addr' id='addr-" addr "'"
                 " style='top:" addr-y "px; left:" addr-x "px;' " addr-attrs ">"
                 "<label>" addr "</label>"
                 (mapv (fn [[[accept-addr accept-port] accept-addr-port-v]]
                         ["<div class='accept-addr-port'>"
                          " <span class='accept-addr'>" accept-addr "</span>"
                          " <span class='accept-port'>" accept-port "</span>"
                          (when (and (= accept-addr addr)
                                     (get (:listens addr-v) accept-port))
                            ["<div class='listen'>" accept-port "</div>"])
                          (mapv
                           (fn [[[from-port to-addr to-port] msgs]]
                             (let [from-x addr-x
                                   from-y (+ addr-y
                                             (* line-height
                                                (get coords [addr from-port])))
                                   [to-addr-x to-addr-y] (calc-xy to-addr)
                                   to-x to-addr-x
                                   to-y (+ to-addr-y
                                           (* line-height
                                              (get coords [to-addr to-port])))
                                   dx (- from-x to-x)
                                   dy (- to-y from-y)
                                   rad (Math/atan2 dx dy)
                                   dist (Math/abs (Math/sqrt (+ (* dx dx)
                                                                (* dy dy))))
                                   prev-msgs (get-in
                                              prev-addrs
                                              [addr :outs
                                               [accept-addr accept-port]
                                               [from-port to-addr to-port]])]
                               ["<div class='port'>"
                                " <div class='port-info'>"
                                from-port " --&gt; "
                                to-addr ":" to-port "</div>"
                                " <div class='msgs-container'>"
                                "  <div class='msgs"
                                (when (= accept-addr addr)
                                  " to-client")
                                "' style='min-height:" (- dist msgs-height-sub) "px;"
                                "transform-origin:top left;"
                                "-ms-transform-origin:top left;"
                                "-webkit-transform-origin:top left;"
                                "transform:rotate(" rad "rad);"
                                "-ms-transform:rotate(" rad "rad);"
                                "-webkit-transform:rotate(" rad "rad);'>"
                                (render-msgs msgs prev-msgs dist line-height geom)
                                "  </div>"
                                " </div>"
                                "</div>"]))
                           (sort-by first accept-addr-port-v))
                          "</div>"])
                       (sort-by first (:outs addr-v)))
                 "</div>"]))
            (sort-by first addrs))
           "</div>"]]
    [addrs h]))

(defn render-net-html [net-state prev-addrs &
                       {:keys [addr-override-xy addr-attrs-fn geom]}]
  (let [addrs (calc-addrs net-state)
        coords (calc-coords addrs)]
    (render-net-addrs-html addrs prev-addrs coords
                           addr-override-xy addr-attrs-fn (or geom {}))))

(defn net-actx-info [vis net-actx-id]
  (first (filter (fn [[actx actx-info]] (= (last actx) net-actx-id))
                 (:actxs vis))))

(defn render-net [vis net-actx-id output-el-id prev-addrs]
  (let [net-state (:loop-state (second (net-actx-info vis net-actx-id)))
        [addrs h] (render-net-html net-state @prev-addrs)]
    (when (not= @prev-addrs addrs)
      (reset! prev-addrs addrs)
      (set-el-innerHTML output-el-id (apply str (flatten h))))))

(defn world-vis-init [el-prefix init-event-delay]
  (let [cmd-inject-ch (chan)
        cmd-ch (replay-cmd-ch cmd-inject-ch (keys cbfg.world.lane/cmd-handlers)
                              (fn [ev]
                                {:op (.-id (.-target ev))
                                 :x (js/parseInt (get-el-value "x"))
                                 :y (js/parseInt (get-el-value "y"))
                                 :delay (js/parseInt (get-el-value "delay"))
                                 :fence (= (get-el-value "fence") "1")
                                 :lane (get-el-value "lane")
                                 :client (get-el-value "client")
                                 :color (get-el-value "color")
                                 :sleep (js/parseInt (get-el-value "sleep"))}))
        render-state (atom {})
        num-clients 3]
    (vis-init (fn [world vis-chs]
                (let [connect-ch (achan-buf world 10)
                      listen-ch (achan-buf world 10)
                      listen-result-ch0 (achan world)
                      listen-result-ch1 (achan world)]
                  (make-net world listen-ch connect-ch)
                  (act init-world world
                       (aput init-world listen-ch [:server 8000 listen-result-ch0])
                       (aput init-world listen-ch [:server 8100 listen-result-ch1])
                       (let [[accept-ch0 close-accept-ch0]
                             (atake init-world listen-result-ch0)
                             [accept-ch1 close-accept-ch1]
                             (atake init-world listen-result-ch1)]
                         (server-accept-loop world accept-ch0 close-accept-ch0)
                         (server-accept-loop world accept-ch1 close-accept-ch1)
                         (let [req-ch (achan init-world)
                               res-ch (achan init-world)
                               server-ports (cycle [8000 8100])
                               client-cmd-chs
                               (reduce
                                (fn [acc i]
                                  (let [client-id (str "client-" i)]
                                    (assoc acc client-id
                                           (client-loop world connect-ch
                                                        :server (nth server-ports
                                                                     (count acc))
                                                        (keyword client-id) res-ch))))
                                {} (range num-clients))]
                           (world-cmd-loop world cbfg.world.lane/cmd-handlers cmd-ch
                                           req-ch res-ch vis-chs world-vis-init el-prefix)
                           (act-loop cmd-dispatch-loop world [num-dispatches 0]
                                     (when-let [msg (atake cmd-dispatch-loop req-ch)]
                                       (when-let [client-cmd-ch
                                                  (get client-cmd-chs (:client msg))]
                                         (aput cmd-dispatch-loop client-cmd-ch msg)
                                         (recur (inc num-dispatches))))))))))
              el-prefix
              #(render-net % "net-1" "net" render-state)
              init-event-delay)
    cmd-inject-ch))

(start-test "net" cbfg.test.net/test)
