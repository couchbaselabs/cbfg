(ns cbfg.world.t2
  (:require-macros [cbfg.act :refer [act act-loop achan achan-buf
                                     aclose aput aput-close atake]])
  (:require [cbfg.fence]
            [cbfg.world.t1 :refer [prog-base-now prog-curr-now prog-evt
                                   wait-done addr-override-xy]]
            [cbfg.world.base]
            [cbfg.world.net]))

(defn state-loop [actx name loop-fn initial-state]
  (let [req-ch (achan actx)]
    (act-loop state-loop actx [name name state initial-state]
              (when-let [m (atake state-loop req-ch)]
                (when-let [[state-next res] (loop-fn state-loop state m)]
                  (when (and res (:res-ch m))
                    (aput-close state-loop (:res-ch m) res))
                  (recur name state-next))))
    req-ch))

; --------------------------------------------

(defn make-initial-server-state [actx]
  {:realms {"_system" {:users {"admin" {:pswd "password"}}
                       :datasets {}}
            "_lobby" {:users {"_anon" {:pswd ""}}
                      :datasets {}}}})

(def initial-lane-state
  {:realm "_lobby" :user "_anon"})

; --------------------------------------------

(defn server-handler [actx server-state m]
  (case (:op m)
    :authenticate
    (if-let [{:keys [realm user pswd]} m]
      (if (and realm user pswd)
        (if (= pswd (get-in server-state [:realms realm :users user :pswd]))
          [server-state (assoc (dissoc m :pswd) :status :ok)]
          [server-state (assoc (dissoc m :pswd) :status :mismatch)])
        [server-state (assoc (dissoc m :pswd) :status :invalid)])
      [server-state (assoc (dissoc m :pswd) :status :invalid)])
    "realms-list"
    (if-let [cred (:cred m)]
      [server-state (assoc m :status :ok :result (keys (:realms server-state)))]
      [server-state (assoc m :status :not-authenticated)])
    nil))

(defn rq-authenticate [actx m]
  (act rq-authenticate actx
       (let [{:keys [server-state-ch lane-state-ch realm user]} m
             res-ch (achan rq-authenticate)]
         (if (aput rq-authenticate server-state-ch
                   (assoc m :op :authenticate :res-ch res-ch))
           (let [res (atake rq-authenticate res-ch)]
             (when (= (:status res) :ok)
               (aput rq-authenticate lane-state-ch
                     {:op :update
                      :update-fn #(assoc % :cred {:realm realm
                                                  :user user})}))
             res)
           (assoc (dissoc m :pswd)
             :status :failed :status-info [:authenticate :closed])))))

; --------------------------------------------

(defn lane-handler [actx lane-state m]
  (case (:op m)
    "realms-list"
    (let [server-state-ch (:server-state-ch m)]
      (act fwd-realms-list actx
           (aput fwd-realms-list server-state-ch
                 (assoc m :cred (:cred lane-state))))
      [lane-state nil])
    nil))

(defn rq-handle-lane [actx m] ; Forward to lane-state-ch to handle the request.
  (act rq-handle-lane actx
       (let [lane-state-ch (:lane-state-ch m)
             res-ch (achan rq-handle-lane)]
         (if (aput rq-handle-lane lane-state-ch (assoc m :res-ch res-ch))
           (atake rq-handle-lane res-ch)
           (assoc m :status :failed
                  :status-info [(:op m) :closed :dispatch-lane])))))

; --------------------------------------------

(def rq-handlers
  {"authenticate" rq-authenticate
   "realms-list" rq-handle-lane
   "add" cbfg.world.base/example-add
   "sub" cbfg.world.base/example-add
   "count" cbfg.world.base/example-count
   "close-lane" cbfg.world.base/close-lane})

(defn rq-handler [actx m] ((get rq-handlers (:op m)) actx m))

; --------------------------------------------

(defn cmd-handler [c] (assoc c :rq rq-handler))

(def cmd-handlers (into {} (map (fn [k] [k cmd-handler]) (keys rq-handlers))))

(defn world-vis-init [el-prefix init-event-delay]
    (cbfg.world.t1/world-vis-init-t "cbfg.world.t2"
                                    cmd-handlers el-prefix
                                    cbfg.world.t1/ev-msg init-event-delay))

; --------------------------------------------

(defn kv-server [server-addr & ports]
  (let [world (:world (prog-base-now))
        server-state-ch (state-loop world :server-state server-handler
                                    (make-initial-server-state world))
        lane-max-inflight 10
        lane-buf-size 20
        make-lane (fn [actx lane-name lane-out-ch]
                    (let [lane-in-ch (achan-buf actx lane-buf-size)
                          lane-state-ch (state-loop actx :lane-state lane-handler
                                                    initial-lane-state)
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
                  #(update-in % [:servers server-addr] conj port)))))
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
