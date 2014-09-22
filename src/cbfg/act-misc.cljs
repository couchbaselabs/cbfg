(ns cbfg.act-misc
  (:require-macros [cbfg.act :refer [act act-loop achan
                                     aclose aput aput-close atake areq]]))

(defn state-loop [actx name loop-fn initial-state & {:keys [req-ch]}]
  (let [req-ch (or req-ch (achan actx))]
    (act-loop state-loop actx [name name state initial-state]
              (when-let [m (atake state-loop req-ch)]
                (when-let [[state-next res] (loop-fn state-loop state m)]
                  (when (and res (:res-ch m))
                    (aput-close state-loop (:res-ch m) res))
                  (recur name state-next))))
    req-ch))

(defn msg-req [actx ch-key m]
  (act msg-req actx (areq msg-req (ch-key m) m)))

(defn msg-put [actx ch-key m]
  (act msg-put actx (when (not (aput msg-put (ch-key m) m))
                      (println "failed msg-put" ch-key m))))

(defn msg-put-res [actx ch-key m]
  (let [res-ch (achan actx)]
    (act msg-put-res actx
         (when (not (aput msg-put-res (ch-key m) (assoc m :res-ch res-ch)))
           (aput-close msg-put-res res-ch
                       (assoc m :status :failed
                              :status-info [(:op m) :closed :msg-put-res]))))
    res-ch))
