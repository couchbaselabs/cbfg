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
; - sync (commits any unpersisted, dirty changes)
;
; Note on res-ch: the kvs always closes the res-ch on request completion.

; TODO: Track age, utilization, compaction for simulated performance.
; TODO: Compaction/maintenance/re-org/vaccuum abstraction.
; TODO: Expiration and expiry races or is that a higher level concern?
; TODO: ABA races.
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
   :clean (make-kc) ; "Persisted", committed kc data after successful :sync op.
   :dirty (make-kc) ; Uncommitted, unsync'ed kc mutations, waiting for :sync op.
   :next-sq 1})

(defn kvs-checker [m cb]
  (fn [state] ; Returns wrapper fn to check if kvs-ident is current.
    (if-let [[name uuid] (:kvs-ident m)]
      (if-let [kvs (get-in state [:kvss name])]
        (if (= uuid (:uuid kvs))
          (cb state kvs)
          [state (merge m {:status :mismatch :status-info [:wrong-uuid (:kvs-ident m)]})])
        [state (merge m {:status :not-found :status-info [:no-kvs (:kvs-ident m)]})])
      [state (merge m {:status :invalid :status-info :missing-kvs-ident-arg})])))

(defn kvs-do [actx state-ch res-ch cb]
  (act kvs-do actx
       (aput kvs-do state-ch [cb res-ch])))

(defn make-scan-fn [scan-kind res-key get-entry]
  (fn [actx state-ch m]
     (let [{:keys [kvs-ident kvs-snapshot kind from to include-deleted res-ch]} m
           cb (fn [state kvs]
                (act scan-work actx
                     (let [kc ((or kind :dirty) kvs)] ; Either :clean or :dirty.
                       (doseq [[k v] (subseq (into (sorted-map)
                                                   (scan-kind kc)) ; :keys or :changes.
                                             >= from < to)]
                         (when-let [entry (get-entry kc k v)]
                           (when (or include-deleted (not (:deleted entry)))
                             (aput scan-work res-ch
                                   (merge m {:more true :status :ok
                                             res-key key :entry entry})))))
                       (aput-close scan-work res-ch (merge m {:status :ok}))))
                nil)]
       (kvs-do actx state-ch res-ch
               (fn [state] ((kvs-checker m cb) (or kvs-snapshot state)))))))

(def default-op-handlers
  {:kvs-list
   (fn [actx state-ch m]
     (kvs-do actx state-ch (:res-ch m)
             (fn [state]
               (act list-names actx
                    (doseq [name (keys (:kvss state))]
                      (aput list-names (:res-ch m) (merge m {:more true :status :ok
                                                             :name name})))
                    (aput-close list-names (:res-ch m) (merge m {:status :ok})))
               nil)))

   :kvs-open
   (fn [actx state-ch m]
     (kvs-do actx state-ch (:res-ch m)
             #(if-let [name (:name m)]
                (if-let [kvs (get-in % [:kvss name])]
                  [% (merge m {:status :ok :status-info :reopened
                               :kvs-ident [name (:uuid kvs)]})]
                  [(-> %
                       (assoc-in [:kvss name] (make-kvs name (:next-uuid %)))
                       (assoc :next-uuid (inc (:next-uuid %))))
                   (merge m {:status :ok :status-info :created
                             :kvs-ident [name (:next-uuid %)]})])
                [% (merge m {:status :invalid :status-info :missing-name-arg})])))

   :kvs-remove
   (fn [actx state-ch m]
     (kvs-do actx state-ch (:res-ch m)
             (kvs-checker m (fn [state kvs]
                              [(dissoc-in state [:kvss (:name kvs)])
                               (merge m {:status :ok
                                         :status-info :removed})]))))

   :multi-get
   (fn [actx state-ch m]
     (let [{:keys [kvs-ident kvs-snapshot kind keys include-deleted res-ch]} m
           res-m (dissoc m :keys)
           cb (fn [state kvs]
                (act multi-get actx
                     (let [kc ((or kind :dirty) kvs)] ; Either :clean or :dirty.
                       (doseq [key keys]
                         (let [entry (kc-entry-by-key kc key)]
                           (if (and entry (or include-deleted (not (:deleted entry))))
                             (aput multi-get res-ch
                                   (merge res-m {:more true :status :ok
                                                 :key key :entry entry}))
                             (aput multi-get res-ch
                                   (merge res-m {:more true :status :not-found
                                                 :key key})))))
                       (aput-close multi-get res-ch (merge res-m {:status :ok}))))
                nil)]
       (kvs-do actx state-ch res-ch
               (fn [state] ((kvs-checker m cb) (or kvs-snapshot state))))))

   :multi-change
   (fn [actx state-ch m]
     (let [res-m (dissoc m :changes)
           res-ch (:res-ch m)
           cb (fn [state kvs]
                (let [next-sq (:next-sq kvs)
                      [next-kvs ress] (reduce (fn [[kvs1 ress] change]
                                                (let [[kvs2 res] (change kvs1 next-sq)]
                                                  [kvs2 (conj ress res)]))
                                              [(assoc kvs :next-sq (inc next-sq)) []]
                                              (:changes m))]
                  (act multi-change actx
                       (doseq [res ress]
                         (aput multi-change res-ch (merge res-m res)))
                       (aput-close multi-change res-ch (merge res-m {:status :ok})))
                  [(assoc-in state [:kvss (:name kvs)] next-kvs) nil]))]
       (kvs-do actx state-ch res-ch (kvs-checker m cb))))

   :scan-keys
   (make-scan-fn :keys :key kc-entry-by-key-sq)

   :scan-changes
   (make-scan-fn :changes :sq (fn [kc sq-key entry] entry))

   :snapshot
   (fn [actx state-ch m]
     (kvs-do actx state-ch (:res-ch m)
             (fn [state] [state (merge m {:status :ok :kvs-snapshot state})])))

   :sync
   (fn [actx state-ch m]
     (kvs-do actx state-ch (:res-ch m)
             (kvs-checker m (fn [state kvs]
                              [(assoc-in state [:kvss (:name kvs)]
                                         (-> kvs
                                             (assoc :clean (:dirty kvs))
                                             (assoc :dirty (make-kc))))
                               (merge m {:status :ok
                                         :status-info [:synced (:kv-ident m)]})]))))})

(defn make-kvs-mgr [actx & {:keys [cmd-ch state-ch op-handlers]}]
  (let [cmd-ch (or cmd-ch (achan actx))
        state-ch (or state-ch (achan actx))]
    (act-loop kvs-mgr-in actx [tot-ops 0]
              (if-let [m (atake kvs-mgr-in cmd-ch)]
                (if-let [op-handler (get (or op-handlers default-op-handlers) (:op m))]
                  (do (op-handler kvs-mgr-in state-ch m)
                      (recur (inc tot-ops)))
                  (println :kvs-mgr-in-exit-unknown-op m))
                (println :kvs-mgr-in-exit-ch)))

    ; Using kvs-mgr-state to avoid atoms, which prevent time-travel.
    (act-loop kvs-mgr-state actx [state {:next-uuid 0 :kvss {}}]
              (if-let [[state-fn done-ch] (atake kvs-mgr-state state-ch)]
                (let [[next-state done-res] (state-fn state)]
                  (when (and done-ch done-res)
                    (aput-close kvs-mgr-state done-ch done-res))
                  (recur (or next-state state)))
                (println :kvs-mgr-state-exit-ch)))
    cmd-ch))

(defn mutate-entry [entry] ; Used for :multi-change.
  (fn [kvs new-sq]
    (let [key (:key entry)
          old-sq (get-in kvs [:dirty :keys key])]
      (if (or (nil? (:sq entry)) (= (:sq entry) old-sq))
        [(-> (if (:deleted entry)
               (dissoc-in kvs [:dirty :keys key])
               (assoc-in kvs [:dirty :keys key] new-sq))
             (dissoc-in [:dirty :changes [old-sq key]])
             (assoc-in [:dirty :changes [new-sq key]] (assoc entry :sq new-sq)))
         {:more true :status :ok :key key :sq new-sq}]
        [kvs {:more true :status :mismatch :key key
              :status-info [:wrong-sq (:sq entry)]}]))))
