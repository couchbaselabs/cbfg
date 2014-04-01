(ns cbfg.store
  (:require-macros [cbfg.ago :refer [ago achan aclose aput]])
  (:require [cbfg.misc :refer [dissoc-in]]))

(defn gen-cas [] (rand-int 0x7fffffff))

(defn return [result atom atom-val]
  (reset! atom atom-val)
  result)

(defn make-store [actx]
  (atom {:keys (sorted-map)    ; key -> sq
         :changes (sorted-map) ; sq -> {:key k, :sq s, <:cas c, :val v> | :deleted true}
         :next-sq 1
         :max-deleted-sq 0}))

(defn store-get [actx store opaque key]
  (ago store-get actx
       (let [s @store
             sq (get (:keys s) key)
             change (get (:changes s) sq)]
         (if (and change (not (:deleted change)))
           {:opaque opaque :status :ok
            :key key :sq sq :cas (:cas change)
            :val (:val change)}
           {:opaque opaque :status :not-found
            :key key}))))

(defn store-set [actx store opaque key old-cas val op]
  (ago store-set actx
       (let [cas-check (and old-cas (not (zero? old-cas)))]
         (if (and cas-check (= op :add))
           {:opaque opaque :status :wrong-cas :key key}
           (let [res (atom nil)]
             (swap! store
                    #(let [s1 (assoc % :next-sq (max (:next-sq %)
                                                     (inc (:max-deleted-sq %))))
                           new-sq (:next-sq s1)
                           old-sq (get-in s1 [:keys key])]
                       (if (and (= op :add) old-sq)
                         (return % res
                                 {:opaque opaque :status :exists :key key})
                         (if (and (or (= op :replace)
                                      (= op :append)
                                      (= op :prepend))
                                  (not old-sq))
                           (return % res
                                   {:opaque opaque :status :not-found :key key})
                           (let [prev-change (when (or cas-check
                                                       (= op :append)
                                                       (= op :prepend))
                                               (get-in s1 [:changes old-sq]))]
                             (if (and cas-check (not= old-cas (:cas prev-change)))
                               (return % res
                                       {:opaque opaque :status :wrong-cas :key key})
                               (let [new-cas (gen-cas)
                                     new-val (case op
                                               :add val
                                               :replace val
                                               :append (str (:val prev-change) val)
                                               :prepend (str val (:val prev-change))
                                               val)]
                                 (return (-> s1
                                             (dissoc-in [:changes old-sq])
                                             (assoc-in [:keys key] new-sq)
                                             (assoc-in [:changes new-sq]
                                                       {:key key :sq new-sq :cas new-cas
                                                        :val new-val})
                                             (update-in [:next-sq] inc))
                                         res {:opaque opaque :status :ok
                                              :key key :sq new-sq :cas new-cas}))))))))
             @res)))))

(defn store-del [actx store opaque key old-cas]
  (ago store-del actx
       (let [res (atom nil)]
         (swap! store
                #(let [old-sq (get-in % [:keys key])
                       new-sq (:next-sq %)
                       cas-check (and old-cas (not (zero? old-cas)))
                       prev-change (when cas-check
                                     (get-in % [:changes old-sq]))]
                   (if (not old-sq)
                     (return % res
                             {:opaque opaque :status :not-found :key key})
                     (if (and cas-check (not= old-cas (:cas prev-change)))
                       (return % res
                               {:opaque opaque :status :wrong-cas :key key})
                       (return (-> %
                                   (assoc :max-deleted-sq (max old-sq
                                                               (:max-deleted-sq %)))
                                   (dissoc-in [:changes old-sq])
                                   (dissoc-in [:keys key])
                                   (assoc-in [:changes new-sq]
                                             {:key key :sq new-sq :deleted true})
                                   (update-in [:next-sq] inc))
                               res {:opaque opaque :status :ok
                                    :key key :sq new-sq})))))
         @res)))

(defn store-scan [actx store opaque from to]
  (let [out (achan actx)]
    (ago store-scan actx
         (let [s @store
               changes (:changes s)]
           (doseq [[key sq]
                   (subseq (into (sorted-map) (:keys s)) >= from < to)]
             (when-let [change (get changes sq)]
               (when (not (:deleted change))
                 (aput store-scan out
                       {:opaque opaque :status :part
                        :key key :sq sq :cas (:cas change)
                        :val (:val change)})))))
         (aput store-scan out {:opaque opaque :status :ok})
         (aclose store-scan out))
    out))

(defn store-changes [actx store opaque from to]
  (let [out (achan actx)]
    (ago store-changes actx
         (let [s @store]
             (doseq [[sq change]
                     (subseq (into (sorted-map) (:changes s)) >= from < to)]
               (aput store-changes out (-> change
                                             (assoc :opaque opaque)
                                             (assoc :status :part)))))
         (aput store-changes out {:opaque opaque :status :ok})
         (aclose store-changes out))
    out))
