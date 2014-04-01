(ns cbfg.world-storage
  (:require-macros [cbfg.ago :refer [ago ago-loop achan achan-buf
                                     aclose aput atake atimeout]])
  (:require [cljs.core.async :refer [<! merge map<]]
            [goog.dom :as gdom]
            [cbfg.vis :refer [dissoc-in listen-el get-el-value
                              get-el-innerHTML set-el-innerHTML
                              vis-init ]]
            [cbfg.fence :refer [make-fenced-pump]]))

(defn gen-cas [] (rand-int 0x7fffffff))

(defn storage-get [actx storage opaque key]
  (ago storage-get actx
       (let [s @storage
             sq (get (:keys s) key)
             change (get (:changes s) sq)]
         (if (and change (not (:deleted change)))
           {:opaque opaque :status :ok
            :key key :sq sq :cas (:cas change)
            :val (:val change)}
           {:opaque opaque :status :not-found
            :key key}))))

(defn storage-set [actx storage opaque key val]
  (ago storage-set actx
       (let [cas (gen-cas)
             s2 (swap! storage
                       #(let [s1 (assoc % :next-sq (max (:next-sq %)
                                                        (inc (:max-deleted-sq %))))
                              sq (:next-sq s1)]
                          (-> s1
                              (dissoc-in [:changes (get-in s1 [:keys key])])
                              (assoc-in [:keys key] sq)
                              (assoc-in [:changes sq]
                                        {:key key :sq sq :cas cas :val val})
                              (update-in [:next-sq] inc))))]
         {:opaque opaque :status :ok
          :key key :sq (dec (:next-sq s2)) :cas cas})))

(defn storage-del [actx storage opaque key]
  (ago storage-del actx
       (let [s2 (swap! storage
                       #(-> %
                            (assoc :max-deleted-sq (max (get-in % [:keys key])
                                                        (:max-deleted-sq %)))
                            (dissoc-in [:changes (get-in % [:keys key])])
                            (dissoc-in [:keys key])
                            (assoc-in [:changes (:next-sq %)]
                                      {:key key :sq (:next-sq %)
                                       :deleted true})
                            (update-in [:next-sq] inc)))]
         {:opaque opaque :status :ok
          :key key :sq (dec (:next-sq s2))})))

(defn storage-scan [actx storage opaque from to]
  (let [out (achan actx)]
    (ago storage-scan actx
         (let [s @storage
               changes (:changes s)]
           (doseq [[key sq] (subseq (:keys s) >= from < to)]
             (when-let [change (get changes sq)]
               (when (not (:deleted change))
                 (aput storage-scan out
                       {:opaque opaque :status :part
                        :key key :sq sq :cas (:cas change)
                        :val (:val change)})))))
         (aput storage-scan out {:opaque opaque :status :ok})
         (aclose storage-scan out))
    out))

(defn storage-changes [actx storage opaque from to]
  (let [out (achan actx)]
    (ago storage-changes actx
         (let [s @storage]
             (doseq [[sq change] (subseq (into (sorted-map) (:changes s)) >= from < to)]
               (aput storage-changes out (-> change
                                             (assoc :opaque opaque)
                                             (assoc :status :part)))))
         (aput storage-changes out {:opaque opaque :status :ok})
         (aclose storage-changes out))
    out))

(defn make-storage-cmd-handlers [storage]
  {"get" [["key"]
          (fn [c] {:rq #(storage-get % storage (:opaque c) (:key c))})]
   "set" [["key" "val"]
          (fn [c] {:rq #(storage-set % storage (:opaque c) (:key c) (:val c))})]
   "del" [["key"]
          (fn [c] {:rq #(storage-del % storage (:opaque c) (:key c))})]
   "scan" [["from" "to"]
           (fn [c] {:rq #(storage-scan % storage (:opaque c) (:from c) (:to c))})]
   "changes" [["from" "to"]
              (fn [c] {:rq #(storage-changes % storage (:opaque c)
                                             (js/parseInt (:from c))
                                             (js/parseInt (:to c)))})]
   "noop" [[] (fn [c] {:rq
                       (fn [actx]
                         (ago storage-cmd-noop actx
                              {:opaque (:opaque c) :status :ok}))})]
   "test" [[] (fn [c] {:rq #(cbfg.fence/test % (:opaque c))})]})

(def storage-max-inflight (atom 10))

(defn el-log [el-prefix log-kind msg]
  (set-el-innerHTML (str el-prefix "-" log-kind "-log")
                    (str msg "\n"
                         (get-el-innerHTML (str el-prefix "-" log-kind "-log")))))

(defn world-vis-init [el-prefix]
  (let [storage (atom {:keys (sorted-map)    ; key -> sq
                       :changes (sorted-map) ; sq -> {:key k, :sq s,
                                             ;        <:cas c, :val v> | :deleted bool}
                       :next-sq 1
                       :max-deleted-sq 0})
        storage-cmd-handlers (make-storage-cmd-handlers storage)
        cmd-ch (map< (fn [ev] {:op (.-id (.-target ev))})
                     (merge (map #(listen-el (gdom/getElement %) "click")
                                 (keys storage-cmd-handlers))))]
    (vis-init (fn [world]
                (let [in-ch (achan-buf world 100)
                      out-ch (achan-buf world 0)]
                  (ago-loop a-input world [num-ins 0]
                            (let [cmd (<! cmd-ch)
                                  op (:op cmd)
                                  op-fence (= (get-el-value (str op "-fence")) "1")
                                  [params handler] (get storage-cmd-handlers op)
                                  cmd2 (-> cmd
                                           (assoc-in [:opaque] num-ins)
                                           (assoc-in [:fence] op-fence))
                                  cmd3 (reduce #(assoc %1 (keyword %2)
                                                       (get-el-value (str op "-" %2)))
                                               cmd2 params)]
                              (el-log el-prefix "input" (str num-ins ": " cmd3))
                              (aput a-input in-ch (handler cmd3))
                              (recur (inc num-ins))))
                  (ago-loop z-output world [num-outs 0]
                            (let [result (atake z-output out-ch)]
                              (el-log el-prefix "output" (str num-outs ": " result))
                              (recur (inc num-outs))))
                  (make-fenced-pump world in-ch out-ch @storage-max-inflight)))
              el-prefix nil)))
