(ns cbfg.world.t2
  (:require-macros [cbfg.act :refer [act act-loop achan aput atake atimeout]])
  (:require [cbfg.world.t1 :refer [prog-base-now prog-curr-now prog-evt
                                   wait-done addr-override-xy]]
            [cbfg.lane]))

(defn world-vis-init [el-prefix init-event-delay]
  (cbfg.world.t1/world-vis-init-t "cbfg.world.t2" el-prefix init-event-delay))

; --------------------------------------------

(defn server-conn-loop [actx server-send-ch server-recv-ch close-server-recv-ch]
  (let [lanes-out-ch (achan actx)]
    (cbfg.lane/make-lane-pump actx server-recv-ch lanes-out-ch
                              cbfg.world.lane/make-fenced-pump-lane)
    (act-loop lanes-out actx [num-outs 0]
              (aput lanes-out server-send-ch [(atake lanes-out lanes-out-ch)])
              (recur (inc num-outs)))))

; --------------------------------------------

(defn kv-server [server-addr & ports]
  (doseq [port ports]
    (let [world (:world (prog-base-now))
          done (atom false)]
      (act server-init world
           (when-let [listen-result-ch (achan server-init)]
             (aput server-init (:net-listen-ch (prog-base-now))
                   [server-addr port listen-result-ch])
             (when-let [[accept-ch close-accept-ch]
                        (atake server-init listen-result-ch)]
               (cbfg.world.net/server-accept-loop world accept-ch close-accept-ch
                                                  :server-conn-loop server-conn-loop)))
           (reset! done true))
      (wait-done done)
      (prog-evt world :kv-server {:server-addr server-addr :server-port port}
                #(update-in % [:servers server-addr] conj port))))
  (addr-override-xy server-addr
                    300 (+ 20 (* 120 (dec (count (:servers (prog-curr-now))))))))

(defn kv-client [client-addr server-addr server-port]
  (let [world (:world (prog-base-now))
        ready (atom false)
        req-ch (cbfg.world.net/client-loop world (:net-connect-ch (prog-base-now))
                                           server-addr server-port
                                           client-addr (:res-ch (prog-base-now))
                                           :start-cb #(reset! ready true))]
    (wait-done ready)
    (prog-evt world :kv-client {:client-addr client-addr
                                :server-addr server-addr
                                :server-port server-port}
              #(assoc-in % [:clients client-addr]
                         {:client-addr client-addr
                          :server-addr server-addr
                          :server-port server-port
                          :req-ch req-ch})))
  (addr-override-xy client-addr
                    20 (+ 20 (* 120 (dec (count (:clients (prog-curr-now))))))))
