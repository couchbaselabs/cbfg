(ns cbfg.world.t2
  (:require-macros [cbfg.act :refer [act act-loop achan achan-buf
                                     aclose aput atake]])
  (:require [cbfg.fence]
            [cbfg.world.t1 :refer [prog-base-now prog-curr-now prog-evt
                                   wait-done addr-override-xy]]
            [cbfg.world.base]
            [cbfg.world.net]))

(def req-handlers
  {"add" cbfg.world.base/example-add
   "sub" cbfg.world.base/example-add
   "count" cbfg.world.base/example-count
   "close-lane" cbfg.world.base/close-lane})

(defn world-vis-init [el-prefix init-event-delay]
  (let [cmd-handler (fn [c] (assoc c :rq #((get req-handlers (:op c)) % c)))
        cmd-handlers (into {} (map (fn [k] [k cmd-handler]) (keys req-handlers)))]
    (cbfg.world.t1/world-vis-init-t "cbfg.world.t2"
                                    cmd-handlers el-prefix
                                    cbfg.world.t1/ev-msg init-event-delay)))

; --------------------------------------------

(defn kv-server [server-addr & ports]
  (let [lane-max-inflight 10
        lane-buf-size 20
        make-lane (fn [actx lane-name lane-out-ch]
                    (let [lane-in-ch (achan-buf actx lane-buf-size)
                          cred-ch (achan actx)
                          cred-cb (fn [is-req msg lane-state]
                                    (if is-req
                                      (if msg
                                        [(assoc msg :cred-ch cred-ch) lane-state]
                                        (do (act close-cred actx
                                                 (aclose close-cred cred-ch))
                                            [msg lane-state]))
                                      [msg lane-state]))]
                      (cbfg.fence/make-fenced-pump actx lane-name
                                                   lane-in-ch lane-out-ch
                                                   lane-max-inflight false
                                                   :ext-cb cred-cb)
                      (act-loop lane-cred actx [cred nil]
                                (let [m (atake lane-cred cred-ch)]
                                  (case (:op m)
                                    :get-cred (do (aput lane-cred (:res-ch m)
                                                        (assoc m :status :ok :result cred))
                                                  (recur cred))
                                    :set-cred (do (aput lane-cred (:res-ch m)
                                                        (assoc m :status :ok))
                                                  (recur (:cred m)))
                                    :mismatch)))
                      lane-in-ch))
        conn-loop (fn [actx server-send-ch server-recv-ch close-server-recv-ch]
                    (cbfg.world.net/server-conn-loop actx server-send-ch server-recv-ch
                                                     close-server-recv-ch
                                                     :make-lane-fn make-lane))]
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
                                                    :server-conn-loop conn-loop)))
             (reset! done true))
        (wait-done done)
        (prog-evt world :kv-server {:server-addr server-addr :server-port port}
                  #(update-in % [:servers server-addr] conj port))))
    (addr-override-xy server-addr
                      300 (+ 20 (* 120 (dec (count (:servers (prog-curr-now)))))))))

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
