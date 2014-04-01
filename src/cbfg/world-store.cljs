(ns cbfg.world-store
  (:require-macros [cbfg.ago :refer [ago ago-loop achan-buf aput atake]])
  (:require [cljs.core.async :refer [chan <! merge map< sliding-buffer]]
            [goog.dom :as gdom]
            [cbfg.fence :refer [make-fenced-pump]]
            [cbfg.fence-test]
            [cbfg.store :as store]
            [cbfg.store-test]
            [cbfg.vis :refer [vis-init listen-el get-el-value log-el]]))

(defn make-store-cmd-handlers [store]
  {"get" [["key"]
          (fn [c] {:rq #(store/store-get % store (:opaque c) (:key c))})]
   "set" [["key" "val"]
          (fn [c] {:rq #(store/store-set % store (:opaque c) (:key c) (:cas c)
                                         (:val c) :set)})]
   "del" [["key"]
          (fn [c] {:rq #(store/store-del % store (:opaque c) (:key c) (:cas c))})]
   "add" [["key" "val"]
          (fn [c] {:rq #(store/store-set % store (:opaque c) (:key c) (:cas c)
                                         (:val c) :add)})]
   "replace" [["key" "val"]
              (fn [c] {:rq #(store/store-set % store (:opaque c) (:key c) (:cas c)
                                             (:val c) :replace)})]
   "append" [["key" "val"]
             (fn [c] {:rq #(store/store-set % store (:opaque c) (:key c) (:cas c)
                                            (:val c) :append)})]
   "prepend" [["key" "val"]
              (fn [c] {:rq #(store/store-set % store (:opaque c) (:key c) (:cas c)
                                             (:val c) :prepend)})]
   "scan" [["from" "to"]
           (fn [c] {:rq #(store/store-scan % store (:opaque c) (:from c) (:to c))})]
   "changes" [["from" "to"]
              (fn [c] {:rq #(store/store-changes % store (:opaque c)
                                                 (js/parseInt (:from c))
                                                 (js/parseInt (:to c)))})]
   "noop" [[] (fn [c] {:rq #(ago store-cmd-noop %
                                 {:opaque (:opaque c) :status :ok})})]
   "test" [[] (fn [c] {:rq #(cbfg.fence-test/test % (:opaque c))})]})

(def store-max-inflight (atom 10))

(defn world-vis-init [el-prefix]
  (let [store (store/make-store)
        store-cmd-handlers (make-store-cmd-handlers store)
        cmd-ch (map< (fn [ev] {:op (.-id (.-target ev))})
                     (merge (map #(listen-el (gdom/getElement %) "click")
                                 (keys store-cmd-handlers))))]
    (vis-init (fn [world]
                (let [in-ch (achan-buf world 100)
                      out-ch (achan-buf world 0)]
                  (ago-loop a-input world [num-ins 0]
                            (let [cmd (<! cmd-ch)
                                  op (:op cmd)
                                  op-fence (= (get-el-value (str op "-fence")) "1")
                                  [params handler] (get store-cmd-handlers op)
                                  cmd2 (-> cmd
                                           (assoc-in [:opaque] num-ins)
                                           (assoc-in [:fence] op-fence))
                                  cmd3 (reduce #(assoc %1 (keyword %2)
                                                       (get-el-value (str op "-" %2)))
                                               cmd2 params)]
                              (log-el el-prefix "input" (str num-ins ": " cmd3))
                              (aput a-input in-ch (handler cmd3))
                              (recur (inc num-ins))))
                  (ago-loop z-output world [num-outs 0]
                            (let [result (atake z-output out-ch)]
                              (log-el el-prefix "output" (str num-outs ": " result))
                              (recur (inc num-outs))))
                  (make-fenced-pump world in-ch out-ch @store-max-inflight)))
              el-prefix nil)))

(let [last-id (atom 0)
      gen-id #(swap! last-id inc)]
  (ago test-actx [{:gen-id gen-id :event-ch (chan (sliding-buffer 1))}]
       (println "store-test:" (<! (cbfg.store-test/test test-actx 0)))))
