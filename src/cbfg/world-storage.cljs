(ns cbfg.world-storage
  (:require-macros [cbfg.ago :refer [ago ago-loop achan-buf aput atake atimeout]])
  (:require [cljs.core.async :refer [<! merge map<]]
            [goog.dom :as gdom]
            [cbfg.vis :refer [dissoc-in listen-el get-el-value
                              get-el-innerHTML set-el-innerHTML
                              vis-init ]]
            [cbfg.fence :refer [make-fenced-pump]]))

(defn gen-cas [] (rand-int 0x7fffffff))

(defn storage-get [storage opaque key]
  (let [s @storage
        sq (get (:keys s) key)
        change (get (:changes s) sq)]
    (if (and change (not (:deleted change)))
      {:opaque opaque :status :ok
       :key key :sq sq :cas (:cas change) :val (:val change)}
      {:opaque opaque :status :not-found
       :key key})))

(defn storage-set [storage opaque key val]
  (let [cas (gen-cas)
        storage2 (swap! storage
                        #(-> %
                             (assoc :next-sq (max (:next-sq %)
                                                  (inc (:max-deleted-sq %))))
                             (dissoc-in [:changes (get-in % [:keys key])])
                             (assoc-in [:keys key] (:next-sq %))
                             (assoc-in [:changes (:next-sq %)]
                                       {:key key :sq (:next-sq %) :cas cas :val val})
                             (update-in [:next-sq] inc)))]
    {:opaque opaque :status :ok
     :key key :sq (dec (:next-sq storage2)) :cas cas}))

(defn storage-del [storage opaque key]
  (let [storage2 (swap! storage
                        #(-> %
                             (assoc :max-deleted-sq (max (get-in % [:keys key])
                                                         (:max-deleted-sq %)))
                             (dissoc-in [:changes (get-in % [:keys key])])
                             (dissoc-in [:keys key])
                             (assoc-in [:changes (:next-sq %)]
                                       {:key key :sq (:next-sq %) :deleted true})
                             (update-in [:next-sq] inc)))]
    {:opaque opaque :status :ok
     :key key :sq (dec (:next-sq storage2))}))

(defn make-storage-cmd-handlers [storage]
  {"get" [["key"]
          (fn [c] {:rq #(ago storage-cmd-get %
                             (assoc (storage-get storage (:opaque c) (:key c))
                               :op "get"))})]
   "set" [["key" "val"]
          (fn [c] {:rq #(ago storage-cmd-set %
                             (assoc (storage-set storage (:opaque c) (:key c) (:val c))
                               :op "set"))})]
   "del" [["key"]
          (fn [c] {:rq #(ago storage-cmd-del %
                             (assoc (storage-del storage (:opaque c) (:key c))
                               :op "del"))})]
   "scan" [["from" "to"]
           (fn [c] {:rq (fn [actx]
                          (ago storage-cmd-scan actx
                               {:opaque (:opaque c) :status :ok
                                :from (:from c) :to (:to c)}))})]
   "changes" [["since"]
              (fn [c] {:rq
                       (fn [actx]
                         (ago storage-cmd-changes actx
                              {:opaque (:opaque c) :status :ok
                               :since (:since c)}))})]
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
                       :max-deleted-sq 1})
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
