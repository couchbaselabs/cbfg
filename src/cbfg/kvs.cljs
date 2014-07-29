(ns cbfg.kvs
  (:require-macros [cbfg.act :refer [act act-loop achan aput-close aput atake]])
  (:require [cbfg.misc :refer [dissoc-in]]))

; A kvs is an abstraction of ordered-KV store.  Concrete examples
; would include couchkvs, forestDB, rocksDB, sqlite, bdb, gklite.
; The mapping is ordered-key => sq => entry, and ordered-sq => entry.
;
; Abstract operations include...
; - multi-get
; - multi-change (a change is an upsert or delete)
; - scan-keys
; - scan-changes
; - snapshot
; - sync (like fsync)
;
; Note on res-ch: the kvs always closes the res-ch on request completion.

; TODO: Track age, utilization, compaction for simulated performance.
; TODO: Compaction/maintenance/re-org/vaccuum abstraction.
; TODO: Readers not blocked by writers.
; TODO: Mutations shadow old values.
; TODO: Simulated slow-disks, full-disks, half-written mutation, rollback, etc.

(defn make-kc [] ; A kc is a keys & changes map.
  {:keys (sorted-map)      ; key -> sq
   :changes (sorted-map)}) ; [sq key] -> {:key k, :sq s, :deleted true | :val v}

(defn kc-sq-by-key [kc key]
  (get (:keys kc) key))

(defn kc-entry-by-key-sq [kc key sq]
  (get (:changes kc) [sq key]))

(defn kc-entry-by-key [kc key]
  (kc-entry-by-key-sq kc key (kc-sq-by-key kc key)))

(defn make-kvs [name uuid]
  {:name name
   :uuid uuid
   :clean (make-kc) ; Read-only.
   :dirty (make-kc) ; Mutations go here, until "persisted / made durable".
   :next-sq 1})

(defn kvs-checker [kvs-ident cb]
  (fn [state] ; Returns wrapper fn to check if kvs-ident is current.
    (if-let [[name uuid] kvs-ident]
      (if-let [kvs (get-in state [:kvss name])]
        (if (= uuid (:uuid kvs))
          (cb state kvs)
          [state {:status :mismatch :status-info [:wrong-uuid kvs-ident]}])
        [state {:status :not-found :status-info [:no-kvs kvs-ident]}])
      [state {:status :invalid :status-info :missing-kvs-ident-arg}])))

(defn kvs-do [actx state-ch res-ch cb]
  (act kvs-do actx
       (aput kvs-do state-ch [cb res-ch])))

(defn kvs-snapshot-do [actx res-ch kvs-snapshot cb]
  (act kvs-snapshot-do actx
       (aput kvs-snapshot-do res-ch (cb kvs-snapshot))))

(defn make-scan-fn [kc-kind scan-kind res-key get-entry]
  (fn [actx state-ch m]
     (let [{:keys [kvs-ident kvs-snapshot from to include-deleted res-ch]} m
           cb (fn [state kvs]
                (act scan-work actx
                     (let [kc (kc-kind kvs)] ; Either :clean or :dirty.
                       (doseq [[k v](subseq (into (sorted-map)
                                                  (scan-kind kc)) ; :keys or :changes.
                                            >= from < to)]
                         (when-let [entry (get-entry kc k v)]
                           (when (or include-deleted (not (:deleted entry)))
                             (aput scan-work res-ch
                                   (merge m {:partial :ok res-key key :entry entry})))))
                       (aput-close scan-work res-ch (merge m {:status :ok}))))
                nil)]
       (if kvs-snapshot
         (kvs-snapshot-do actx res-ch kvs-snapshot (kvs-checker kvs-ident cb))
         (kvs-do actx state-ch nil (kvs-checker kvs-ident cb))))))

(def op-handlers
  {:kvs-open
   (fn [actx state-ch m]
     (kvs-do actx state-ch (:res-ch m)
             #(if-let [name (:name m)]
                (if-let [kvs (get-in % [:kvss name])]
                  [% (merge m {:status :ok :status-info :reopened
                               :kvs-ident [name (:uuid kvs)]})]
                  [(-> %
                       (update-in [:kvss name] (make-kvs name (:next-uuid %)))
                       (assoc :next-uuid (inc (:next-uuid %))))
                   (merge m {:status :ok :status-info :created
                             :kvs-ident [name (:next-uuid %)]})])
                [% (merge m {:status :invalid :status-info :missing-name-arg})])))

   :kvs-remove
   (fn [actx state-ch m]
     (kvs-do actx state-ch (:res-ch m)
             (kvs-checker (:kvs-ident m)
                          (fn [state kvs]
                            [(dissoc-in state [:kvss (:name kvs)])
                             {:status :ok :status-info [:removed (:kvs-ident m)]}]))))

   :multi-get
   (fn [actx state-ch m]
     (let [{:keys [kvs-ident kvs-snapshot res-ch]} m
           res-m (dissoc m :keys)
           cb (fn [state kvs]
                (act multi-get actx
                     (let [kc (:clean kvs)]
                       (doseq [key (:keys m)]
                         (if-let [entry (kc-entry-by-key kc key)]
                           (aput multi-get res-ch
                                 (merge res-m {:partial :ok :key key :entry entry}))
                           (aput multi-get res-ch
                                 (merge res-m {:partial :not-found :key key}))))
                       (aput-close multi-get res-ch (merge res-m {:status :ok}))))
                nil)]
       (if kvs-snapshot
         (kvs-snapshot-do actx res-ch kvs-snapshot (kvs-checker kvs-ident cb))
         (kvs-do actx state-ch nil (kvs-checker kvs-ident cb)))))

   :multi-change
   (fn [actx state-ch m]
     (let [res-m (dissoc m :changes)
           res-ch (:res-ch m)
           cb (fn [state kvs]
                (let [next-sq (:next-sq kvs)
                      [next-kvs ress] (reduce (fn [[kvs ress] change]
                                                (let [[kvs res] (change kvs next-sq)]
                                                  [kvs (conj ress res)]))
                                              [(assoc kvs :next-sq (inc next-sq)) []]
                                              (:changes m))]
                  (act multi-change actx
                       (doseq [res ress]
                         (aput multi-change res-ch (merge res-m res)))
                       (aput-close multi-change res-ch (merge res-m {:status :ok})))
                  [(assoc-in state [:kvss (:name kvs)] next-kvs) nil]))]
       (kvs-do actx state-ch nil (kvs-checker (:kvs-ident m) cb))))

   :scan-keys
   (make-scan-fn :clean :keys :key kc-entry-by-key-sq)

   :scan-changes
   (make-scan-fn :clean :changes :sq (fn [kc sq-key entry] entry))

   :snapshot
   (fn [actx state-ch m]
     (kvs-do actx state-ch (:res-ch m)
             (fn [state] [state {:status :ok :kvs-snapshot state}])))

   :sync
   (fn [actx state-ch m]
     (kvs-do actx state-ch (:res-ch m)
             (kvs-checker (:kvs-ident m)
                          (fn [state kvs]
                            [(assoc-in state [:kvss (:name kvs)]
                                       (-> kvs
                                           (assoc :clean (:dirty kvs))
                                           (assoc :dirty (make-kc))))
                             {:status :ok :status-info [:synced (:kv-ident m)]}]))))})

(defn make-kvs-mgr [actx & op-handlers-in & {:keys [cmd-ch state-ch]}]
  (let [cmd-ch (or cmd-ch (achan actx))
        state-ch (or state-ch (achan actx))]
    (act-loop kvs-mgr-in actx [tot-ops 0]
              (if-let [m (atake kvs-mgr-in cmd-ch)]
                (if-let [op-handler (get (or op-handlers-in op-handlers) (:op m))]
                  (do (op-handler kvs-mgr-in state-ch m)
                      (recur (inc tot-ops)))
                  (println :kvs-mgr-in-exit-unknown-op m))
                (println :kvs-mgr-in-exit-ch)))

    ; Using kvs-mgr-state to avoid atoms, which prevent time-travel.
    (act-loop kvs-mgr-state actx [state { :next-uuid 0 :kvss {} }]
              (if-let [[state-fn done-ch] (atake kvs-mgr-state state-ch)]
                (let [[next-state done-res] (state-fn state)]
                  (when (and done-ch done-res)
                    (aput-close kvs-mgr-state done-ch done-res))
                  (recur (or next-state state)))
                (println :kvs-mgr-state-exit-ch)))
    cmd-ch))
