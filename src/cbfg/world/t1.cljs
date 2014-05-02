(ns cbfg.world.t1
  (:require-macros [cbfg.act :refer [act act-loop achan-buf aalts aput]])
  (:require [cljs.core.async :refer [chan]]
            [cbfg.vis :refer [vis-init get-el-value]]
            [cbfg.fence :refer [make-fenced-pump]]
            [cbfg.store :as store]
            [cbfg.test.store]
            [cbfg.world.base :refer [replay-cmd-ch world-replay
                                     render-client-hist start-test]]))

(defn make-store-cmd-handlers [store]
  {"get"
   [["key"]
    (fn [c] #(store/store-get % store (:opaque c) (:key c)))]
   "set"
   [["key" "val"]
    (fn [c] #(store/store-set % store (:opaque c) (:key c) (:cas c)
                              (:val c) :set))]
   "del"
   [["key"]
    (fn [c] #(store/store-del % store (:opaque c) (:key c) (:cas c)))]
   "add"
   [["key" "val"]
    (fn [c] #(store/store-set % store (:opaque c) (:key c) (:cas c)
                              (:val c) :add))]
   "replace"
   [["key" "val"]
    (fn [c] #(store/store-set % store (:opaque c) (:key c) (:cas c)
                              (:val c) :replace))]
   "append"
   [["key" "val"]
    (fn [c] #(store/store-set % store (:opaque c) (:key c) (:cas c)
                              (:val c) :append))]
   "prepend"
   [["key" "val"]
    (fn [c] #(store/store-set % store (:opaque c) (:key c) (:cas c)
                              (:val c) :prepend))]
   "scan"
   [["from" "to"]
    (fn [c] #(store/store-scan % store (:opaque c)
                               (:from c) (:to c)))]
   "changes"
   [["from" "to"]
    (fn [c] #(store/store-changes % store (:opaque c)
                                  (js/parseInt (:from c))
                                  (js/parseInt (:to c))))]
   "noop"
   [[] (fn [c] #(act store-cmd-noop %
                     {:opaque (:opaque c) :status :ok}))]
   "test"
   [[] (fn [c] #(cbfg.test.store/test % (:opaque c)))]})

(def store-max-inflight (atom 10))

(defn world-vis-init [el-prefix init-event-delay]
  (let [cmd-inject-ch (chan)]
    (vis-init (fn [world vis-chs]
                (let [store (store/make-store world)
                      store-cmd-handlers (make-store-cmd-handlers store)
                      cmd-ch (replay-cmd-ch cmd-inject-ch (keys store-cmd-handlers)
                                            (fn [ev] {:op (.-id (.-target ev))}))
                      client-hist (atom {}) ; Keyed by opaque -> [request, replies]
                      in-ch (achan-buf world 100)
                      out-ch (achan-buf world 0)]
                  (act-loop client world [num-ins 0 num-outs 0]
                            (let [[v ch] (aalts client [cmd-ch out-ch])
                                  ts (+ num-ins num-outs)]
                              (cond
                               (= ch cmd-ch) (if (= (:op v) "replay")
                                               (world-replay in-ch vis-chs world-vis-init el-prefix
                                                             @client-hist (:replay-to v))
                                               (let [op (:op v)
                                                     op-fence (= (get-el-value (str op "-fence")) "1")
                                                     [params handler] (get store-cmd-handlers op)
                                                     cmd2 (-> v
                                                              (assoc :opaque ts)
                                                              (assoc :fence op-fence))
                                                     cmd3 (reduce #(if (get %1 (keyword %2))
                                                                     %1 ; Allows cmd2 to have precedence.
                                                                     (assoc %1 (keyword %2)
                                                                            (get-el-value (str op "-" %2))))
                                                                  cmd2 params)]
                                                 (render-client-hist (swap! client-hist
                                                                            #(assoc % ts [cmd3 nil])))
                                                 (aput client in-ch (assoc cmd3 :rq (handler cmd3)))
                                                 (recur (inc num-ins) num-outs)))
                               (= ch out-ch) (when v
                                               (do (render-client-hist (swap! client-hist
                                                                              #(update-in % [(:opaque v) 1]
                                                                                          conj [ts v])))
                                                   (recur num-ins (inc num-outs)))))))
                  (make-fenced-pump world "main" in-ch out-ch @store-max-inflight true)))
              el-prefix nil init-event-delay)
    cmd-inject-ch))

(start-test "store" cbfg.test.store/test)
