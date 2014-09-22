(ns cbfg.world.t2
  (:require-macros [cbfg.act :refer [act act-loop achan achan-buf
                                     aclose aput aput-close atake areq]])
  (:require [clojure.string :as string]
            [cbfg.act-misc :refer [state-loop msg-req msg-put msg-put-res]]
            [cbfg.vis :refer [get-el-value get-el-checked get-el-className get-els]]
            [cbfg.fence]
            [cbfg.kvs]
            [cbfg.world.t1 :refer [prog-base-now prog-curr-now prog-evt
                                   wait-done addr-override-xy]]
            [cbfg.world.base]
            [cbfg.world.net]))

(defn coll-handler [actx coll-state m]
  (case (:op m)
    "coll-state"
    [coll-state (assoc m :status :ok :value coll-state)]

    [coll-state (assoc m :status :invalid :status-info [:unknown-op m])]))

(defn make-coll [actx rev path kvs-mgr-ch]
  (let [coll-ch (achan actx)]
    (act coll-init actx
         (let [res (areq coll-init kvs-mgr-ch {:op :kvs-open :name [path rev]})]
           (if (= (:status res) :ok)
             (state-loop actx [:coll path rev] coll-handler
                         {:kvs-ident (:kvs-ident res)}
                         :req-ch coll-ch)
             (do (println "make-coll kvs-open failed" res)
                 ; TODO: Should also consume any reqs that raced onto coll-ch.
                 (aclose coll-init coll-ch)))))
    {:rev rev
     :path path
     :coll-ch coll-ch
     :kvs-mgr-ch kvs-mgr-ch}))

; --------------------------------------------

(defn make-initial-server-state [actx]
  (let [kvs-mgr-ch (cbfg.kvs/make-kvs-mgr actx)
        default-coll (make-coll actx 0 ["_lobby" "default" "default"]
                                kvs-mgr-ch)]
    {:rev 0
     :realms {"_system" {:rev 0
                         :users {"admin" {:rev 0 :pswd "password"}}
                         :collsets {}}
              "_lobby" {:rev 0
                        :users {"_anon" {:rev 0 :pswd ""}}
                        :collsets {"default" {:rev 0
                                              :colls {"default" default-coll}}}}}
     :kvs-mgr-ch kvs-mgr-ch}))

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
                  coll (make-coll actx nrev
                                  [(:cur-realm m) (:cur-realm-collset m) (:key m)]
                                  (:kvs-mgr-ch server-state))]
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

    [server-state (assoc m :status :invalid :status-info :invalid-op)]))

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

    "coll-state"
    (if-let [coll-ch (get-in lane-state [:cur-coll :coll-ch])]
      (do (msg-put actx (fn [_] coll-ch) (merge m lane-state))
          [lane-state nil])
      [lane-state (assoc m :status :failed :status-info :missing-coll)])

    ; ELSE
    (do (msg-put actx :server-state-ch (merge m lane-state))
        [lane-state nil])))

(def initial-lane-state
  {:cur-realm "_lobby"
   :cur-realm-collset "default"
   :cur-realm-collset-coll "default"
   :cur-coll nil ; Either nil or result of make-coll.
   :cur-user-realm "_lobby"
   :cur-user "_anon"})

(defn lane-state-loop [actx server-state-ch]
  (let [req-ch (achan actx)]
    (act lane-state-loop-init actx
         (let [ils initial-lane-state
               res (areq lane-state-loop-init server-state-ch
                         {:op :authenticate
                          :user-realm (:cur-user-realm ils)
                          :user (:cur-user ils)
                          :pswd ""
                          :realm (:cur-realm ils)
                          :realm-collset (:cur-realm-collset ils)
                          :realm-collset-coll (:cur-realm-collset-coll ils)})]
           (state-loop actx :lane-state lane-handler
                       (assoc initial-lane-state :cur-coll (:coll res))
                       :req-ch req-ch)))
    req-ch))

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
   "coll-state" #(msg-put-res %1 :lane-state-ch %2)
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
                          lane-state-ch (lane-state-loop actx server-state-ch)
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
