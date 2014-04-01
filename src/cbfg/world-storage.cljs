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

(defn storage-set [actx storage opaque key old-cas val op]
  (ago storage-set actx
       (let [cas-check (and old-cas (not (zero? old-cas)))]
         (if (and cas-check (= op :add))
           {:opaque opaque :status :wrong-cas :key key}
           (let [res (atom nil)]
             (swap! storage
                    #(let [s1 (assoc % :next-sq (max (:next-sq %)
                                                     (inc (:max-deleted-sq %))))
                           new-sq (:next-sq s1)
                           old-sq (get-in s1 [:keys key])]
                       (if (and (= op :add) old-sq)
                         (do (reset! res {:opaque opaque :status :exists
                                          :key key})
                             s1)
                         (let [prev-change (when (or cas-check
                                                     (= op :append)
                                                     (= op :prepend))
                                             (get-in s1 [:changes old-sq]))]
                           (if (and cas-check (not= old-cas (:cas prev-change)))
                             (do (reset! res {:opaque opaque :status :wrong-cas
                                              :key key})
                                 s1)
                             (if (and (or (= op :replace)
                                          (= op :append)
                                          (= op :prepend))
                                      (not old-sq))
                               (do (reset! res {:opaque opaque :status :not-found
                                                :key key})
                                   s1)
                               (let [new-cas (gen-cas)
                                     new-val (case op
                                               :add val
                                               :replace val
                                               :append (str (:val prev-change) val)
                                               :prepend (str val (:val prev-change))
                                               val)]
                                 (reset! res {:opaque opaque :status :ok
                                              :key key :sq new-sq :cas new-cas})
                                 (-> s1
                                     (dissoc-in [:changes old-sq])
                                     (assoc-in [:keys key] new-sq)
                                     (assoc-in [:changes new-sq]
                                               {:key key :sq new-sq :cas new-cas
                                                :val new-val})
                                     (update-in [:next-sq] inc)))))))))
             @res)))))

(defn storage-del [actx storage opaque key old-cas]
  (ago storage-del actx
       (let [res (atom nil)]
         (swap! storage
                #(let [old-sq (get-in % [:keys key])
                       cas-check (and old-cas (not (zero? old-cas)))
                       prev-change (when cas-check
                                     (get-in % [:changes old-sq]))]
                   (if (not old-sq)
                     (do (reset! res {:opaque opaque :status :not-found
                                      :key key})
                         s1)
                     (if (and cas-check (not= old-cas (:cas prev-change)))
                       (do (reset! res {:opaque opaque :status :wrong-cas
                                        :key key})
                           s1)
                       (let [new-sq (:next-sq %)]
                         (reset! res {:opaque opaque :status :wrong-cas
                                      :key key :sq new-sq})
                         (-> %
                             (assoc :max-deleted-sq (max old-sq
                                                         (:max-deleted-sq %)))
                             (dissoc-in [:changes old-sq])
                             (dissoc-in [:keys key])
                             (assoc-in [:changes new-sq]
                                       {:key key :sq new-sq :deleted true})
                             (update-in [:next-sq] inc)))))))
         @res)))

(defn storage-scan [actx storage opaque from to]
  (let [out (achan actx)]
    (ago storage-scan actx
         (let [s @storage
               changes (:changes s)]
           (doseq [[key sq]
                   (subseq (into (sorted-map) (:keys s)) >= from < to)]
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
             (doseq [[sq change]
                     (subseq (into (sorted-map) (:changes s)) >= from < to)]
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
          (fn [c] {:rq #(storage-set % storage (:opaque c) (:key c) (:cas c) (:val c) :set)})]
   "del" [["key"]
          (fn [c] {:rq #(storage-del % storage (:opaque c) (:key c) (:cas c))})]
   "add" [["key" "val"]
          (fn [c] {:rq #(storage-set % storage (:opaque c) (:key c) (:cas c) (:val c) :add)})]
   "replace" [["key" "val"]
          (fn [c] {:rq #(storage-set % storage (:opaque c) (:key c) (:cas c) (:val c) :replace)})]
   "append" [["key" "val"]
          (fn [c] {:rq #(storage-set % storage (:opaque c) (:key c) (:cas c) (:val c) :append)})]
   "prepend" [["key" "val"]
          (fn [c] {:rq #(storage-set % storage (:opaque c) (:key c) (:cas c) (:val c) :prepend)})]
   "scan" [["from" "to"]
           (fn [c] {:rq #(storage-scan % storage (:opaque c) (:from c) (:to c))})]
   "changes" [["from" "to"]
              (fn [c] {:rq #(storage-changes % storage (:opaque c)
                                             (js/parseInt (:from c))
                                             (js/parseInt (:to c)))})]
   "noop" [[] (fn [c] {:rq #(ago storage-cmd-noop %
                                 {:opaque (:opaque c) :status :ok})})]
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
