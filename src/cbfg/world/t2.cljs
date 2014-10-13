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

(def max-int (.-MAX_SAFE_INTEGER js/Number))

(defn parse-sq [s]
  (if (= s "")
    nil
    (js/parseInt s)))

(defn item-multi-op [actx partition-state m req-fn]
  (let [res-ch (achan actx)]
    (act item-op actx
         (aput item-op (:kvs-mgr-ch partition-state)
               (dissoc (assoc (req-fn res-ch)
                         :kvs-ident (:kvs-ident partition-state))
                       :res-ch))
         (aput item-op (:res-ch m)
               (dissoc (atake item-op res-ch) :more))
         (while (atake item-op res-ch)))
    [partition-state nil]))

(defn partition-handler [actx partition-state m]
  (case (:op m)
    "partition-state"
    [partition-state (assoc m :status :ok :value partition-state)]

    "item-get"
    (item-multi-op actx partition-state m
                   #(assoc m :op :multi-get
                           :key-reqs [{:res-ch %
                                       :key (:key m)
                                       :sq (parse-sq (:sq m))}]))

    "item-set"
    (item-multi-op actx partition-state m
                   #(assoc m :op :multi-change
                           :change-reqs [{:res-ch %
                                          :change-fn
                                          (cbfg.kvs/mutate-entry
                                           {:key (:key m)
                                            :val (:val m)
                                            :sq (parse-sq (:sq m))})}]))

    "item-del"
    (item-multi-op actx partition-state m
                   #(assoc m :op :multi-change
                           :change-reqs [{:res-ch %
                                          :change-fn
                                          (cbfg.kvs/mutate-entry
                                           {:deleted true
                                            :key (:key m)
                                            :sq (parse-sq (:sq m))})}]))

    "item-scan-keys"
    (do (act item-scan-keys actx
             (aput item-scan-keys (:kvs-mgr-ch partition-state)
                   (assoc m :op :scan-keys
                          :kvs-ident (:kvs-ident partition-state))))
        [partition-state nil])

    "item-scan-changes"
    (do (act item-scan-changes actx
             (aput item-scan-changes (:kvs-mgr-ch partition-state)
                   (assoc m :op :scan-changes
                          :kvs-ident (:kvs-ident partition-state)
                          :from (js/parseInt (or (parse-sq (:from m)) 0))
                          :to (js/parseInt (or (parse-sq (:to m)) max-int)))))
        [partition-state nil])

    [partition-state (assoc m :status :invalid :status-info :invalid-op)]))

; A partition can be used like a vbucket / partition.
; Any partition-meta data is handled as just more entries in the partition,
; somewhat like "dot files" in a filesystem.

(defn make-partition [actx sq path kvs-mgr-ch]
  (let [partition-ch (achan actx)
        partition-res {:sq sq
                       :path path
                       :partition-ch partition-ch
                       :kvs-mgr-ch kvs-mgr-ch}]
    (act partition-init actx
         (let [res (areq partition-init kvs-mgr-ch {:op :kvs-open :name [path sq]})]
           (if (= (:status res) :ok)
             (state-loop actx [:partition path sq] partition-handler
                         (assoc partition-res :kvs-ident (:kvs-ident res))
                         :req-ch partition-ch)
             (do (println "make-partition kvs-open failed" res)
                 ; TODO: Should also consume any reqs that raced onto partition-ch.
                 (aclose partition-init partition-ch)))))
    partition-res))

; --------------------------------------------

(defn make-initial-server-state [actx]
  (let [kvs-mgr-ch (cbfg.kvs/make-kvs-mgr actx)
        ; TODO: Put grouper in front of kvs-mgr-ch to batch up requests.
        ; TODO: Have kvs-mgr instances per realm instead of per server.
        default-partition (make-partition actx 0 ["_lobby" "default" "default"]
                                          kvs-mgr-ch)]
    {:sq 0
     :realms {"_system" {:sq 0
                         :users {"admin" {:sq 0 :pswd "password"}}
                         :partitions {}}
              "_lobby" {:sq 0
                        :users {"_anon" {:sq 0 :pswd ""}}
                        :partitions {"default" default-partition}}}
     :kvs-mgr-ch kvs-mgr-ch}))

(defn server-handler [actx server-state m]
  (case (:op m)
    :authenticate
    (if-let [{:keys [user-realm user pswd]} m]
      (if (and user-realm user pswd
               (= pswd (get-in server-state
                               [:realms user-realm :users user :pswd])))
        [server-state (assoc (dissoc m :pswd) :status :ok
                             :partition
                             (get-in server-state
                                     [:realms (:realm m)
                                      :partitions (:realm-partition m)]))]
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

    "partitions-list"
    ; TODO: Support from & to parameters for partitions-list.
    (do (act partitions-list actx
             (doseq [name (keys (get-in server-state
                                        [:realms (:cur-realm m)
                                         :partitions]))]
               (aput partitions-list (:res-ch m)
                     (assoc m :status :ok :more true :value name)))
             (aput-close partitions-list (:res-ch m)
                         (assoc m :status :ok)))
        [server-state nil])

    "partition-create"
    (if (or (= (:cur-user-realm m) (:cur-realm m))
            (= (:cur-user-realm m) "_system"))
      (if (and (:key m) (re-matches #"^[a-zA-Z][a-zA-Z0-9_-]+" (:key m)))
        (if-let [partitions
                 (get-in server-state [:realms (:cur-realm m)
                                       :partitions])]
          (if (not (get (:key m) partitions))
            (let [nsq (inc (:sq server-state))
                  partition
                  (make-partition actx nsq
                                  [(:cur-realm m) (:key m)]
                                  (:kvs-mgr-ch server-state))]
              ; TODO: audit log on success and failure.
              [(-> server-state
                   (assoc-in [:realms (:cur-realm m)
                              :partitions (:key m)] partition)
                   (assoc-in [:realms (:cur-realm m)
                              :sq] nsq)
                   (assoc-in [:realms (:cur-realm m)
                              :sq] nsq)
                   (assoc :sq nsq))
               (assoc m :status :ok :sq nsq)])
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
      (if (:partition m)
        [(assoc lane-state
           :cur-realm (:realm m)
           :cur-realm-partition (:realm-partition m)
           :cur-partition (:partition m)
           :cur-user-realm (:user-realm m)
           :cur-user (:user m))
         (assoc m :status :ok)]
        [lane-state (assoc m :status :invalid :status-info :unknown-partition)])
      [lane-state (assoc m :status :invalid :status-info :wrong-realm)])

    "lane-state"
    [lane-state (assoc m :status :ok :value lane-state)]

    ("partition-state"
     "item-get" "item-set" "item-del"
     "item-scan-keys" "item-scan-changes")
    (if-let [partition-ch (get-in lane-state [:cur-partition :partition-ch])]
      (do (msg-put actx (fn [_] partition-ch) (merge m lane-state))
          [lane-state nil])
      [lane-state (assoc m :status :failed :status-info :missing-partition)])

    ; ELSE
    (do (msg-put actx :server-state-ch (merge m lane-state))
        [lane-state nil])))

(def initial-lane-state
  {:cur-realm "_lobby"
   :cur-realm-partition "default"
   :cur-partition nil ; Either nil or result of make-partition.
   :cur-user-realm "_lobby"
   :cur-user "_anon"})

(defn lane-state-loop [actx server-state-ch initial-lane-state]
  (let [req-ch (achan actx)]
    (act lane-state-loop-init actx
         (let [ils initial-lane-state
               res (areq lane-state-loop-init server-state-ch
                         {:op :authenticate
                          :user-realm (:cur-user-realm ils)
                          :user (:cur-user ils)
                          :pswd ""
                          :realm (:cur-realm ils)
                          :realm-partition (:cur-realm-partition ils)})]
           (state-loop actx :lane-state lane-handler
                       (assoc ils :cur-partition (:partition res))
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
                 (assoc m :op :update-user-realm :partition (:partition res)))
           res))))

(def rq-handlers
  {"authenticate" rq-authenticate
   "realms-list" #(msg-put-res %1 :lane-state-ch %2)
   "partitions-list" #(msg-put-res %1 :lane-state-ch %2)
   "partition-state" #(msg-put-res %1 :lane-state-ch %2)
   "partition-create" #(msg-put-res %1 :lane-state-ch %2)
   "item-get" #(msg-put-res %1 :lane-state-ch %2)
   "item-set" #(msg-put-res %1 :lane-state-ch %2)
   "item-del" #(msg-put-res %1 :lane-state-ch %2)
   "item-scan-keys" #(msg-put-res %1 :lane-state-ch %2)
   "item-scan-changes" #(msg-put-res %1 :lane-state-ch %2)
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

(defn init-server [server-kind server-handler server-addr ports
                   make-server-state initial-lane-state]
  (let [world (:world (prog-base-now))
        server-state-ch (state-loop world [server-kind server-addr ports]
                                    server-handler
                                    (make-server-state world))
        lane-max-inflight 10
        lane-buf-size 20
        make-lane (fn [actx lane-name lane-out-ch]
                    (let [lane-in-ch (achan-buf actx lane-buf-size)
                          lane-state-ch (lane-state-loop actx server-state-ch
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
        (prog-evt world server-kind {:server-addr server-addr :server-port port}
                  #(update-in % [:servers server-addr] conj port)))))
  (addr-override-xy server-addr
                    300 (+ 20 (* 120 (dec (count (:servers (prog-curr-now))))))))

(defn kv-server [server-addr & ports] ; Data manager.
  (init-server :kv-server server-handler server-addr ports
               make-initial-server-state initial-lane-state))

; --------------------------------------------

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
