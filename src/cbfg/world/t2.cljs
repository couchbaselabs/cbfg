(ns cbfg.world.t2
  (:require-macros [cbfg.act :refer [act act-loop achan achan-buf
                                     aclose aput atake]])
  (:require [cbfg.fence]
            [cbfg.world.t1 :refer [prog-base-now prog-curr-now prog-evt
                                   wait-done addr-override-xy]]
            [cbfg.world.base]
            [cbfg.world.net]))

(def initial-server-state
  {:default-realm "_lobby"
   :realms {"_system" {:default-user nil
                       :users {"admin" {:pswd "password"}}}
            "_lobby" {:default-user "_anon"
                      :users {"_anon" {:pswd ""}}}}})

(defn rq-authenticate [actx m]
  (act rq-authenticate actx
       (let [{:keys [server-state-ch lane-state-ch realm user pswd]} m
             r (dissoc m :pswd)]
         (if (and realm user pswd)
           (let [res-ch (achan rq-authenticate)]
             (if (aput rq-authenticate server-state-ch {:op :get :res-ch res-ch})
               (let [server-state (atake rq-authenticate res-ch)]
                 (if (= pswd (get-in server-state
                                     [:realms realm :users user :pswd]))
                   (do (aput rq-authenticate lane-state-ch
                             {:op :update
                              :update-fn #(assoc % :cred {:realm realm
                                                          :user user})})
                       (assoc r :status :ok))
                   (assoc r :status :failed
                          :status-info [:authenticate-failed :mismatch])))
               (assoc r :status :failed
                      :status-info [:authenticate-failed :closed])))
           (assoc r :status :invalid
                  :status-info [:missing-args :authenticate])))))

; --------------------------------------------

(def rq-handlers
  {"authenticate" rq-authenticate
   "add" cbfg.world.base/example-add
   "sub" cbfg.world.base/example-add
   "count" cbfg.world.base/example-count
   "close-lane" cbfg.world.base/close-lane})

(defn rq-dispatch [actx m] ((get rq-handlers (:op m)) actx m))

; --------------------------------------------

(defn cmd-handler [c] (assoc c :rq #(rq-dispatch % c)))

(def cmd-handlers (into {} (map (fn [k] [k cmd-handler]) (keys rq-handlers))))

(defn world-vis-init [el-prefix init-event-delay]
    (cbfg.world.t1/world-vis-init-t "cbfg.world.t2"
                                    cmd-handlers el-prefix
                                    cbfg.world.t1/ev-msg init-event-delay))

; --------------------------------------------

(defn start-state-loop [actx name initial-state]
  (let [req-ch (achan actx)]
    (act-loop state-loop actx [name name state initial-state]
              (let [m (atake state-loop req-ch)]
                (case (:op m)
                  :get (do (aput state-loop (:res-ch m)
                                 (assoc m :status :ok :value state))
                           (recur name state))
                  :update (do (when (:res-ch m)
                                (aput state-loop (:res-ch m)
                                      (assoc m :status :ok)))
                              (recur name ((:update-fn m) state)))
                  :unknown-op)))
    req-ch))

; --------------------------------------------

(defn kv-server [server-addr & ports]
  (let [world (:world (prog-base-now))
        server-state-ch (start-state-loop world :server-state initial-server-state)
        lane-max-inflight 10
        lane-buf-size 20
        make-lane (fn [actx lane-name lane-out-ch]
                    (let [lane-in-ch (achan-buf actx lane-buf-size)
                          lane-state-ch (start-state-loop actx
                                                          :lane-state {:realm "_lobby"
                                                                       :user "_anon"})
                          lane-state-cb (fn [is-req m lane-ext-state]
                                          (if is-req
                                            (if m
                                              [(assoc m :lane-state-ch lane-state-ch
                                                      :server-state-ch server-state-ch)
                                               lane-ext-state]
                                              (do (act closer actx
                                                       (aclose closer lane-state-ch))
                                                  [m lane-ext-state]))
                                            [m lane-ext-state]))]
                      (cbfg.fence/make-fenced-pump actx lane-name
                                                   lane-in-ch lane-out-ch
                                                   lane-max-inflight false
                                                   :ext-cb lane-state-cb)
                      lane-in-ch))
        conn-loop (fn [actx server-send-ch server-recv-ch close-server-recv-ch]
                    (cbfg.world.net/server-conn-loop actx
                                                     server-send-ch
                                                     server-recv-ch
                                                     close-server-recv-ch
                                                     :make-lane-fn make-lane))]
    (doseq [port ports]
      (let [done (atom false)]
        (act port-init world
             (when-let [listen-result-ch (achan port-init)]
               (aput port-init (:net-listen-ch (prog-base-now))
                     [server-addr port listen-result-ch])
               (when-let [[accept-ch close-accept-ch]
                          (atake port-init listen-result-ch)]
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
