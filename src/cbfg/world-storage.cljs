(ns cbfg.world-storage
  (:require-macros [cbfg.ago :refer [ago ago-loop achan-buf aput atake atimeout]])
  (:require [cljs.core.async :refer [<! merge map<]]
            [goog.dom :as gdom]
            [cbfg.vis :refer [vis-init listen-el get-el-value
                              get-el-innerHTML set-el-innerHTML]]
            [cbfg.fence :refer [make-fenced-pump]]))

(defn storage-get [actx opaque-id key]
  (ago storage-get actx
       {:opaque-id opaque-id :status :ok :key key :val "yay"}))

(defn storage-set [actx opaque-id key val]
  (ago storage-set actx
       {:opaque-id opaque-id :status :ok}))

(defn storage-del [actx opaque-id key]
  (ago storage-del actx
       {:opaque-id opaque-id :status :ok}))

(defn storage-scan [actx opaque-id from to]
  (ago storage-del actx
       {:opaque-id opaque-id :status :ok}))

(defn storage-noop [actx opaque-id]
  (ago storage-del actx
       {:opaque-id opaque-id :status :ok}))

(def storage-cmd-handlers
  {"get"  [["key"]
           (fn [c] {:rq #(storage-get % (:opaque-id c) (:key c))})]
   "set"  [["key" "val"]
           (fn [c] {:rq #(storage-set % (:opaque-id c) (:key c) (:val c))})]
   "del"  [["key"]
           (fn [c] {:rq #(storage-del % (:opaque-id c) (:key c))})]
   "scan" [["from" "to"]
           (fn [c] {:rq #(storage-scan % (:opaque-id c) (:from c) (:to c))})]
   "noop" [[]
           (fn [c] {:rq #(storage-noop % (:opaque-id c))})]
   "test" [[]
           (fn [c] {:rq #(cbfg.fence/test %)})]})

(def storage-max-inflight (atom 10))

(defn world-vis-init [el-prefix]
  (let [cmd-ch (map< (fn [ev] {:op (.-id (.-target ev))})
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
              el-prefix)))
