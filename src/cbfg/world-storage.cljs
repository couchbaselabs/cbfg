(ns cbfg.world-storage
  (:require-macros [cbfg.ago :refer [ago ago-loop achan-buf aput atake atimeout]])
  (:require [cljs.core.async :refer [<! merge map<]]
            [goog.dom :as gdom]
            [cbfg.vis :refer [vis-init listen-el get-el-value
                              get-el-innerHTML set-el-innerHTML]]
            [cbfg.fence :refer [make-fenced-pump]]))

(defn make-storage-cmd-handlers [kvs changes last-seq max-deleted-seq]
  {"get" [["key"]
          (fn [c] {:rq (fn [actx]
                         (ago storage-get actx
                              {:opaque-id (:opaque-id c) :status :ok
                               :key (:key c) :val "yay"}))})]
   "set" [["key" "val"]
          (fn [c] {:rq (fn [actx]
                         (ago storage-get actx
                              {:opaque-id (:opaque-id c) :status :ok
                               :key (:key c)}))})]
   "del" [["key"]
          (fn [c] {:rq (fn [actx]
                         (ago storage-get actx
                              {:opaque-id (:opaque-id c) :status :ok
                               :key (:key c)}))})]
   "scan" [["from" "to"]
           (fn [c] {:rq (fn [actx]
                          (ago storage-get actx
                               {:opaque-id (:opaque-id c) :status :ok
                                :from (:from c) :to (:to c)}))})]
   "changes" [["since"]
              (fn [c] {:rq
                       (fn [actx]
                         (ago storage-get actx
                              {:opaque-id (:opaque-id c) :status :ok
                               :since (:since c)}))})]
   "noop" [[] (fn [c] {:rq
                       (fn [actx]
                         (ago storage-get actx
                              {:opaque-id (:opaque-id c) :status :ok}))})]
   "test" [[] (fn [c] {:rq #(cbfg.fence/test % (:opaque-id c))})]})

(def storage-max-inflight (atom 10))

(defn el-log [el-prefix log-kind msg]
  (set-el-innerHTML (str el-prefix "-" log-kind "-log")
                    (str msg "\n"
                         (get-el-innerHTML (str el-prefix "-" log-kind "-log")))))

(defn world-vis-init [el-prefix]
  (let [kvs (atom {})
        changes (atom {})
        last-seq (atom 0)
        max-deleted-seq (atom 0)
        storage-cmd-handlers (make-storage-cmd-handlers kvs changes
                                                        last-seq max-deleted-seq)
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
                                           (assoc-in [:opaque-id] num-ins)
                                           (assoc-in [:fence] op-fence))
                                  cmd3 (reduce #(assoc %1
                                                  (symbol (str ":" %2))
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
