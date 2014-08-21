(ns cbfg.world.t2
  (:require-macros [cbfg.act :refer [act achan achan-buf aput atake]])
  (:require [cbfg.fence]
            [cbfg.world.t1 :refer [prog-base-now prog-curr-now prog-evt
                                   wait-done addr-override-xy]]
            [cbfg.world.base]
            [cbfg.world.net]))

(defn make-t2-cmd-handlers [st]
  {"add"   (fn [c] (assoc c :rq #(cbfg.world.base/example-add % c)))
   "sub"   (fn [c] (assoc c :rq #(cbfg.world.base/example-sub % c)))
   "count" (fn [c] (assoc c :rq #(cbfg.world.base/example-count % c)))
   "close-lane" (fn [c] (assoc c :op :close-lane))})

(defn world-vis-init [el-prefix init-event-delay]
  (cbfg.world.t1/world-vis-init-t "cbfg.world.t2"
                                  (make-t2-cmd-handlers nil) el-prefix
                                  cbfg.world.t1/ev-msg init-event-delay))

; --------------------------------------------

(defn make-fenced-pump-lane [actx lane-name lane-out-ch]
  (let [max-inflight 10
        lane-buf-size 20
        lane-in-ch (achan-buf actx lane-buf-size)]
    (cbfg.fence/make-fenced-pump actx lane-name lane-in-ch lane-out-ch
                                 max-inflight false)
    lane-in-ch))

(defn server-conn-loop [actx server-send-ch server-recv-ch close-server-recv-ch]
  (cbfg.world.net/server-conn-loop actx server-send-ch server-recv-ch
                                   close-server-recv-ch
                                   :make-lane-fn make-fenced-pump-lane))

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
