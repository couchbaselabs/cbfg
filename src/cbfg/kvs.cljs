(ns cbfg.kvs
  (:require-macros [cbfg.act :refer [act act-loop achan aput-close aput atake]])
  (:require [cbfg.misc :refer [dissoc-in]]))

; A kvs is an abstraction of ordered-KV store.  Concrete examples
; would include couchkvs, forestDB, rocksDB, sqlite, bdb, gklite.
; The mapping is ordered key => sq => item, and ordered sq => item.
; Abstract operations include...
; - multi-get
; - multi-change (a change is an upsert or delete).
; - scan-keys
; - scan-changes
; - fsync
; - stats
; - has compaction/maintenance abstraction

; TODO: Track age, utilization, compaction for simulated performance.
; TODO: Cannot use atoms because they prevent time travel.
; TODO: Add snapshot ability.
; TODO: Readers not blocked by writers.

(defn make-kc [] ; A kc is a keys / changes map.
  {:keys (sorted-map)      ; key -> sq
   :changes (sorted-map)}) ; [sq key] -> {:key k, :sq s, :deleted true | :val v}

(defn make-kvs [name uuid]
  {:name name
   :uuid uuid
   :clean (make-kc) ; Read-only.
   :dirty (make-kc) ; Mutations go here, until "persisted / made durable".
   :next-sq 1})

(defn kc-sq-by-key [kc key]
  (get (:keys kc) key))

(defn kc-entry-by-key-sq [kc key sq]
  (get (:changes kc) [sq key]))

(defn kc-entry-by-key [kc key]
  (kc-entry-by-key-sq kc key (kc-sq-by-key kc key)))

(defn kvs-ident-check [kvs-ident work-fn]
  ; Wrapper that checks to see if kvs-ident refers to a current kvs.
  (fn [state] ; Meant to be used as work-fn for state-work function.
    (if-let [[name uuid] kvs-ident]
      (if-let [kvs (get-in state [:kvss name])]
        (if (= uuid (:uuid kvs))
          (work-fn state kvs)
          [state {:status :mismatch :status-info :wrong-uuid}])
        [state {:status :not-found :status-info :no-kvs}])
      [state {:status :invalid :status-info :no-kvs-ident}])))

(defn state-work [actx state-ch m work-fn]
  (let [done-ch (achan actx)]
    (act state-work actx
         (aput state-work state-ch [work-fn done-ch]))
         (aput state-work (:res-ch m)
               (merge m (or (atake state-work done-ch)
                            {:status :error :status-info :closed-ch})))))

(def op-handlers
  {:kvs-open
   (fn [actx state-ch m]
     (state-work actx state-ch m
                 #(if-let [name (:name m)]
                    (if-let [kvs (get-in % [:kvss name])]
                      [% {:status :ok :status-info :reopened
                          :kvs-ident [name (:uuid kvs)]}]
                      [(-> %
                           (update-in [:kvss name] (make-kvs name (:next-uuid %)))
                           (assoc :next-uuid (inc (:next-uuid %))))
                       {:status :ok :status-info :created
                        :kvs-ident [name (:next-uuid %)]}])
                    [% {:status :invalid :status-info :no-name}])))

   :kvs-remove
   (fn [actx state-ch m]
     (state-work actx state-ch m
                 (kvs-ident-check (:kvs-ident m)
                                  (fn [state kvs]
                                    [(dissoc-in state [:kvss (:name kvs)])
                                     {:status :ok :status-info :removed}]))))

   :multi-get
   (fn [actx state-ch m]
     (let [keys (:keys m)
           res-m (dissoc m :status :status-info :partial :keys)
           res-ch (:res-ch m)
           work-fn (fn [state kvs]
                     (act multi-get actx
                          (let [kc-clean (:clean kvs)]
                            (doseq [key (:keys m)]
                              (if-let [entry (kc-entry-by-key kc-clean key)]
                                (aput multi-get res-ch
                                      (merge res-m
                                             {:partial :ok :key key :entry entry}))
                                (aput multi-get res-ch
                                      (merge res-m
                                             {:partial :not-found :key key}))))
                            (aput multi-get res-ch {:status :ok})))
                     [state nil])]
       (act multi-get-start actx
         (aput multi-get-start state-ch [work-fn nil]))))

   :multi-change
   (fn [actx state-ch [res-ch name changes]])

   :scan-keys
   (fn [actx state-ch [res-ch name from-key to-key]])

   :scan-changes
   (fn [actx state-ch [res-ch name from-sq to-sq]])

   :sync
   (fn [actx state-ch [res-ch name]])})

(defn make-kvs-mgr [actx]
  (let [cmd-ch (achan actx)
        state-ch (achan actx)]
    (act-loop kvs-mgr-in actx [tot-ops 0]
              (if-let [m (atake kvs-mgr-in cmd-ch)]
                (if-let [op-handler (get op-handlers (:op m))]
                  (do (op-handler kvs-mgr-in state-ch m)
                      (recur (inc tot-ops)))
                  (println :kvs-mgr-in-unknown-op-exit m))
                (println :kvs-mgr-in-ch-exit)))

    ; Using kvs-mgr-state to avoid atoms, which prevent time-travel.
    (act-loop kvs-mgr-state actx [state { :next-uuid 0 :kvss {} }]
              (if-let [[state-fn done-ch] (atake kvs-mgr-state state-ch)]
                (if-let [[next-state done-res] (state-fn state)]
                  (do (when (and done-ch done-res)
                        (aput-close kvs-mgr-state done-ch done-res))
                      (recur next-state))
                  (println :kvs-mgr-state-fn-exit))
                (println :kvs-mgr-state-ch-exit)))
    cmd-ch))
