(ns cbfg.world.t2
  (:require-macros [cbfg.act :refer [act act-loop achan achan-buf
                                     aclose aput aput-close atake areq]])
  (:require [clojure.string :as string]
            [cbfg.vis :refer [get-el-value get-el-checked get-el-className get-els]]
            [cbfg.fence]
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

(defn msg-req [actx ch-key m]
  (act msg-req actx (areq msg-req (ch-key m) m)))

(defn msg-put [actx ch-key m]
  (act msg-put actx (aput msg-put (ch-key m) m)))

(defn msg-put-res [actx ch-key m]
  (let [res-ch (achan actx)]
    (act msg-put-res actx
         (when (not (aput msg-put-res (ch-key m) (assoc m :res-ch res-ch)))
           (aput-close msg-put-res res-ch
                       (assoc m :status :failed
                              :status-info [(:op m) :closed :msg-put-res]))))
    res-ch))

; --------------------------------------------

; TODO: Split between logical schema and physical runtime objects.

(defn coll-handler [actx coll-state m]
  [coll-state nil])

(defn make-coll [actx rev name]
  (let [coll-ch (achan actx)]
    (state-loop actx [:coll name] coll-handler {})
    {:rev rev
     :name name
     :coll-ch coll-ch}))

(defn make-initial-server-state [actx]
  (let [c (make-coll actx 0 "default")]
    {:rev 0
     :realms {"_system" {:rev 0
                         :users {"admin" {:rev 0 :pswd "password"}}
                         :collsets {}}
              "_lobby" {:rev 0
                        :users {"_anon" {:rev 0 :pswd ""}}
                        :collsets {"default" {:rev 0
                                              :colls {"default" c}}}}}}))

(def initial-lane-state
  {:cur-realm "_lobby"
   :cur-realm-collset "default"
   :cur-realm-collset-coll "default"
   :cur-user-realm "_lobby"
   :cur-user "_anon"}) ; TODO: Need cur-coll, too.

; --------------------------------------------

(defn server-handler [actx server-state m]
  (case (:op m)
    :authenticate
    (if-let [{:keys [user-realm user pswd]} m]
      (if (and user-realm user pswd
               (= pswd (get-in server-state
                               [:realms user-realm :users user :pswd])))
        [server-state (assoc (dissoc m :pswd) :status :ok
                             :coll (get-in server-state
                                           [:realms (:realm m)
                                            :collsets (:realm-collset m)
                                            :colls (:realm-collset-coll m)]))]
        [server-state (assoc (dissoc m :pswd) :status :invalid)])
      [server-state (assoc (dissoc m :pswd) :status :invalid :status-info :args)])

    "realms-list"
    (let [realm-keys (if (= (:cur-user-realm m) "_system")
                       (keys (:realms server-state))
                       [(:cur-user-realm m)])]
      (act realms-list actx [realm-keys realm-keys]
           (doseq [realm-key realm-keys]
             (aput realms-list (:res-ch m)
                   (assoc m :status :ok :more true :value realm-key)))
           (aput-close realms-list (:res-ch m)
                       (assoc m :status :ok)))
      [server-state nil])

    "collsets-list"
    (do (act collsets-list actx
             (doseq [name (keys (get-in server-state
                                        [:realms (:cur-realm m) :collsets]))]
               (aput collsets-list (:res-ch m)
                     (assoc m :status :ok :more true :value name)))
             (aput-close collsets-list (:res-ch m)
                         (assoc m :status :ok)))
        [server-state nil])

    "colls-list"
    (do (act colls-list actx
             (doseq [name (keys (get-in server-state
                                        [:realms (:cur-realm m)
                                         :collsets (:cur-realm-collset m)
                                         :colls]))]
               (aput colls-list (:res-ch m)
                     (assoc m :status :ok :more true :value name)))
             (aput-close colls-list (:res-ch m)
                         (assoc m :status :ok)))
        [server-state nil])

    "coll-create"
    (if (or (= (:cur-user-realm m) (:cur-realm m))
            (= (:cur-user-realm m) "_system"))
      (if (and (:key m) (re-matches #"^[a-zA-Z][a-zA-Z0-9_-]+" (:key m)))
        (if-let [colls (get-in server-state [:realms (:cur-realm m)
                                             :collsets (:cur-realm-collset m)
                                             :colls])]
          (if (not (get (:key m) colls))
            (let [nrev (inc (:rev server-state))
                  coll (make-coll actx nrev (:key m))]
              ; TODO: audit log on success and failure.
              [(-> server-state
                   (assoc-in [:realms (:cur-realm m)
                              :collsets (:cur-realm-collset m)
                              :colls (:key m)] coll)
                   (assoc-in [:realms (:cur-realm m)
                              :collsets (:cur-realm-collset m)
                              :rev] nrev)
                   (assoc-in [:realms (:cur-realm m)
                              :rev] nrev)
                   (assoc :rev nrev))
               (assoc m :status :ok :rev nrev)])
            [server-state (assoc m :status :exists)])
          [server-state (assoc m :status :not-found)])
        [server-state (assoc m :status :invalid :status-info :bad-key)])
      [server-state (assoc m :status :invalid :status-info :auth)])

    nil))

; --------------------------------------------

(defn lane-handler [actx lane-state m]
  (case (:op m)
    :update-user-realm
    (if (or (= (:user-realm m) (:realm m))
            (= (:user-realm m) "_system"))
      (if (:coll m)
        [(assoc lane-state
           :cur-realm (:realm m)
           :cur-realm-collset (:realm-collset m)
           :cur-realm-collset-coll (:realm-collset-coll m)
           :cur-coll (:coll m)
           :cur-user-realm (:user-realm m)
           :cur-user (:user m))
         (assoc m :status :ok)]
        [lane-state (assoc m :status :invalid :status-info :unknown-coll)])
      [lane-state (assoc m :status :invalid :status-info :wrong-realm)])

    "lane-state"
    [lane-state (assoc m :status :ok :value lane-state)]

    ; ELSE
    (do (msg-put actx :server-state-ch (merge m lane-state))
        [lane-state nil])))

; --------------------------------------------

(defn rq-authenticate [actx m]
  (act rq-authenticate actx
       (let [{:keys [server-state-ch lane-state-ch user-realm user realm]} m
             res (areq rq-authenticate server-state-ch
                       (assoc m :op :authenticate))]
         (if (= (:status res) :ok)
           (areq rq-authenticate lane-state-ch
                 (assoc m :op :update-user-realm :coll (:coll res)))
           res))))

(def rq-handlers
  {"authenticate" rq-authenticate
   "realms-list" #(msg-put-res %1 :lane-state-ch %2)
   "collsets-list" #(msg-put-res %1 :lane-state-ch %2)
   "colls-list" #(msg-put-res %1 :lane-state-ch %2)
   "coll-create" #(msg-put-res %1 :lane-state-ch %2)
   "add" cbfg.world.base/example-add
   "sub" cbfg.world.base/example-add
   "count" cbfg.world.base/example-count
   "lane-close" #(println "NEVER REACHED / :lane-close handled by lane pump")
   "lane-state" #(msg-req %1 :lane-state-ch %2)})

(defn rq-handler [actx m] ((get rq-handlers (:op m)) actx m))

; --------------------------------------------

(def op-map {"lane-close" :lane-close})

(defn ev-msg [ev]
  ; Populate msg only with args for the given op.
  (let [op (first (filter get-el-checked (keys rq-handlers)))
        msg (assoc (cbfg.world.t1/ev-msg ev) :op (get op-map op op))]
    (reduce (fn [msg class-name]                 ; class-name looks like "arg-lane".
              (let [arg-name (subs class-name 4) ; Strip "arg-", so "lane".
                    key (keyword arg-name)]
                (assoc msg key (get msg key (get-el-value arg-name)))))
            msg
            (filter #(.match % #"^arg-")
                    (string/split (get-el-className op) #" ")))))

(defn world-vis-init [el-prefix init-event-delay]
    (cbfg.world.t1/world-vis-init-t "cbfg.world.t2" ["go"]
                                    #(assoc % :rq rq-handler)
                                    el-prefix ev-msg init-event-delay))

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
