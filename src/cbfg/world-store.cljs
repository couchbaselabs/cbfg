(ns cbfg.world-store
  (:require-macros [cbfg.ago :refer [ago ago-loop achan-buf aclose aalts aput]])
  (:require [cljs.core.async :refer [chan <! merge map< sliding-buffer]]
            [goog.dom :as gdom]
            [cbfg.fence :refer [make-fenced-pump]]
            [cbfg.store :as store]
            [cbfg.store-test]
            [cbfg.vis :refer [vis-init listen-el get-el-value]]
            [cbfg.world-base :refer [world-replay render-client-hist]]))

(defn make-store-cmd-handlers [store]
  {"get"
   [["key"]
    (fn [c] {:rq #(store/store-get % store (:opaque c) (:key c))})]
   "set"
   [["key" "val"]
    (fn [c] {:rq #(store/store-set % store (:opaque c) (:key c) (:cas c)
                                   (:val c) :set)})]
   "del"
   [["key"]
    (fn [c] {:rq #(store/store-del % store (:opaque c) (:key c) (:cas c))})]
   "add"
   [["key" "val"]
    (fn [c] {:rq #(store/store-set % store (:opaque c) (:key c) (:cas c)
                                   (:val c) :add)})]
   "replace"
   [["key" "val"]
    (fn [c] {:rq #(store/store-set % store (:opaque c) (:key c) (:cas c)
                                   (:val c) :replace)})]
   "append"
   [["key" "val"]
    (fn [c] {:rq #(store/store-set % store (:opaque c) (:key c) (:cas c)
                                   (:val c) :append)})]
   "prepend"
   [["key" "val"]
    (fn [c] {:rq #(store/store-set % store (:opaque c) (:key c) (:cas c)
                                   (:val c) :prepend)})]
   "scan"
   [["from" "to"]
    (fn [c] {:rq #(store/store-scan % store (:opaque c)
                                    (:from c) (:to c))})]
   "changes"
   [["from" "to"]
    (fn [c] {:rq #(store/store-changes % store (:opaque c)
                                       (js/parseInt (:from c))
                                       (js/parseInt (:to c)))})]
   "noop"
   [[] (fn [c] {:rq #(ago store-cmd-noop %
                          {:opaque (:opaque c) :status :ok})})]
   "test"
   [[] (fn [c] {:rq #(cbfg.store-test/test % (:opaque c))})]
   "replay"
   [:never-reached]})

(def store-max-inflight (atom 10))

(defn world-vis-init [el-prefix]
  (let [cmd-inject-ch (chan)]
    (vis-init (fn [world vis-chs]
                (let [store (store/make-store world)
                      store-cmd-handlers (make-store-cmd-handlers store)
                      cmd-ch (merge [cmd-inject-ch
                                     (map< (fn [ev] {:op (.-id (.-target ev))})
                                           (merge (map #(listen-el (gdom/getElement %) "click")
                                                       (keys store-cmd-handlers))))])
                      client-hist (atom {}) ; Keyed by opaque -> [request, replies]
                      in-ch (achan-buf world 100)
                      out-ch (achan-buf world 0)]
                  (ago-loop client world [num-ins 0 num-outs 0]
                            (let [[v ch] (aalts client [cmd-ch out-ch])
                                  ts (+ num-ins num-outs)]
                              (cond
                               (= ch cmd-ch) (if (= (:op v) "replay")
                                               (do (aclose client in-ch)
                                                   (doseq [vis-ch (vals vis-chs)]
                                                     (aclose client vis-ch))
                                                   (world-replay world-vis-init el-prefix @client-hist))
                                               (let [op (:op v)
                                                     op-fence (= (get-el-value (str op "-fence")) "1")
                                                     [params handler] (get store-cmd-handlers op)
                                                     cmd2 (-> v
                                                              (assoc :opaque ts)
                                                              (assoc :fence op-fence))
                                                     cmd3 (reduce #(assoc %1 (keyword %2)
                                                                          (get-el-value (str op "-" %2)))
                                                                  cmd2 params)]
                                                 (render-client-hist (swap! client-hist
                                                                            #(assoc % ts [cmd3 nil])))
                                                 (aput client in-ch (cljs.core/merge cmd3 (handler cmd3)))
                                                 (recur (inc num-ins) num-outs)))
                               (= ch out-ch) (do (render-client-hist (swap! client-hist
                                                                            #(update-in % [(:opaque v) 1]
                                                                                        conj [ts v])))
                                                 (recur num-ins (inc num-outs))))))
                  (make-fenced-pump world in-ch out-ch @store-max-inflight)))
              el-prefix nil)
    cmd-inject-ch))

(let [last-id (atom 0)
      gen-id #(swap! last-id inc)]
  (ago test-actx [{:gen-id gen-id :event-ch (chan (sliding-buffer 1))}]
       (println "store-test:"
                (<! (cbfg.store-test/test test-actx 0)))))
