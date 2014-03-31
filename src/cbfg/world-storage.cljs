(ns cbfg.world-storage
  (:require-macros [cbfg.ago :refer [ago ago-loop achan-buf aput atake atimeout]])
  (:require [cljs.core.async :refer [<! merge map<]]
            [goog.dom :as gdom]
            [cbfg.vis :refer [vis-init listen-el get-el-value
                              get-el-innerHTML set-el-innerHTML]]
            [cbfg.fence :refer [make-fenced-pump]]))

(defn make-storage-cmd-handlers [kvs changes last-seq max-deleted-seq]
  {"get"  [["key"]
           (fn [c] {:rq
                    (fn [actx]
                      (ago storage-get actx
                           {:opaque-id (:opaque-id c) :status :ok
                            :key (:key c) :val "yay"}))})]
   "set"  [["key" "val"]
           (fn [c] {:rq
                    (fn [actx]
                      (ago storage-get actx
                           {:opaque-id (:opaque-id c) :status :ok
                            :key (:key c)}))})]
   "del"  [["key"]
           (fn [c] {:rq
                    (fn [actx]
                      (ago storage-get actx
                           {:opaque-id (:opaque-id c) :status :ok
                            :key (:key c)}))})]
   "scan" [["from" "to"]
           (fn [c] {:rq
                    (fn [actx]
                      (ago storage-get actx
                           {:opaque-id (:opaque-id c) :status :ok
                            :from (:from c) :to (:to c)}))})]
   "noop" [[]
           (fn [c] {:rq
                    (fn [actx]
                      (ago storage-get actx
                           {:opaque-id (:opaque-id c) :status :ok}))})]
   "test" [[]
           (fn [c] {:rq #(cbfg.fence/test % (:opaque-id c))})]})

(def storage-max-inflight (atom 10))

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
                (let [input-log (str el-prefix "-input-log")
                      output-log (str el-prefix "-output-log")
                      in-ch (achan-buf world 100)
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
                              (set-el-innerHTML input-log
                                                (str num-ins ": " cmd3 "\n"
                                                     (get-el-innerHTML input-log)))
                              (aput a-input in-ch (handler cmd3))
                              (recur (inc num-ins))))
                  (ago-loop z-output world [num-outs 0]
                            (let [result (atake z-output out-ch)]
                              (set-el-innerHTML output-log
                                                (str num-outs ": " result "\n"
                                                     (get-el-innerHTML output-log)))
                              (recur (inc num-outs))))
                  (make-fenced-pump world in-ch out-ch @storage-max-inflight)))
              el-prefix nil)))
