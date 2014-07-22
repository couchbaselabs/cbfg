(ns cbfg.kvs
  (:require-macros [cbfg.act :refer [act act-loop achan aput atake aalts]])
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

(defn make-kvs [name uuid]
  {:name name
   :uuid uuid
   :keys (sorted-map)    ; key -> sq
   :changes (sorted-map) ; sq -> {:key k, :sq s, :deleted true | :val v}
   :next-sq 1})

; TODO: Track age, utilization, compaction for simulated performance.

; TODO: Cannot use atoms because they prevent time travel.

(def op-handlers
  {:kvs-open
   (fn [actx kvss m]
     (act kvs-open actx
          (let [{:keys [res-ch name]} m
                kvs (get-in (swap! kvss
                                   #(if (get-in % [:kvss name])
                                      %
                                      (let [next-uuid (or (:next-uuid %) 0)]
                                        (-> %
                                            (update-in [:kvss name]
                                                       (make-kvs name next-uuid))
                                            (update-in [:next-uuid]
                                                       (inc next-uuid))))))
                            [:kvss name])]
            (aput kvs-open res-ch (merge m {:kvs kvs})))))

   :kvs-remove
   (fn [actx kvss m]
     (act kvs-remove actx
          (let [{:keys [res-ch name]} m
                err (atom nil)]
            (swap! kvss #(if (get-in % [:kvss name])
                           (dissoc-in % [:kvss name])
                           (do (reset! err :missing)
                               %)))
            (aput kvs-remove res-ch (merge m {:err err})))))

   :multi-get
   (fn [actx kvss [res-ch name keys]]
     (act kvs-multi-get actx))

   :multi-change
   (fn [actx kvss [res-ch name changes]])

   :scan-keys
   (fn [actx kvss [res-ch name from-key to-key]])

   :scan-changes
   (fn [actx kvss [res-ch name from-sq to-sq]])

   :sync
   (fn [actx kvss [res-ch name]])})

(defn make-kvs-mgr [actx]
  (let [cmd-ch (achan actx)
        kvss (atom {})] ; { :next-uuid 0
                        ;   :kvss => { name => kvs } }.
    (act-loop kvs actx [tot-ops 0]
              (when-let [m (atake kvs cmd-ch)]
                (if-let [op-handler (get op-handlers (:op m))]
                  (do (op-handler kvs kvss m)
                      (recur (inc tot-ops)))
                  (println :UNKNOWN-KVS-OP m))))
    cmd-ch))
