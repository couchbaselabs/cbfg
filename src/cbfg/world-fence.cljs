(ns cbfg.world-fence
  (:require-macros [cbfg.ago :refer [ago ago-loop aclose achan achan-buf
                                     aalts aput atake atimeout]])
  (:require [clojure.string :as string]
            [cljs.core.async :refer [chan <! merge map< filter< sliding-buffer]]
            [goog.dom :as gdom]
            [cbfg.vis :refer [vis-init listen-el get-el-value]]
            [cbfg.fence :refer [make-fenced-pump]]
            [cbfg.fence-test]
            [cbfg.world-base :refer [world-replay render-client-hist]]))

(def cmd-handlers
  {"add"    (fn [c] (assoc c :rq #(cbfg.world-base/example-add % c)))
   "sub"    (fn [c] (assoc c :rq #(cbfg.world-base/example-sub % c)))
   "count"  (fn [c] (assoc c :rq #(cbfg.world-base/example-count % c)))
   "test"   (fn [c] (assoc c :rq #(cbfg.fence-test/test % (:opaque c))))})

(def max-inflight (atom 10))

(defn world-vis-init [el-prefix]
  (let [cmd-inject-ch (chan)
        cmd-ch (merge [cmd-inject-ch
                       (map< #(let [[op x] (string/split (.-id (.-target %)) #"-")]
                                {:op op :x x})
                             (filter< #(= "BUTTON" (.-tagName (.-target %)))
                                      (listen-el (gdom/getElement "client") "click")))
                       (map< (fn [ev] {:op (.-id (.-target ev))
                                       :x (js/parseInt (get-el-value "x"))
                                       :y (js/parseInt (get-el-value "y"))
                                       :delay (js/parseInt (get-el-value "delay"))
                                       :fence (= (get-el-value "fence") "1")})
                             (merge (map #(listen-el (gdom/getElement %) "click")
                                         (conj (keys cmd-handlers) "replay"))))])
        client-hist (atom {})] ; Keyed by opaque -> [request, replies].
    (vis-init (fn [world vis-chs]
                (let [in-ch (achan-buf world 100)
                      out-ch (achan-buf world 0)]
                  (ago-loop client world [num-ins 0 num-outs 0]
                            (let [[v ch] (aalts client [cmd-ch out-ch])
                                  ts (+ num-ins num-outs)]
                              (cond
                               (= ch cmd-ch) (if (= (:op v) "replay")
                                               (do (aclose client in-ch)
                                                   (doseq [vis-ch (vals vis-chs)]
                                                     (aclose client vis-ch))
                                                   (world-replay world-vis-init el-prefix
                                                                 @client-hist (:x v)))
                                               (let [cmd (assoc-in v [:opaque] ts)
                                                     cmd-rq ((get cmd-handlers (:op cmd)) cmd)]
                                                 (render-client-hist (swap! client-hist
                                                                            #(assoc % ts [cmd nil])))
                                                 (aput client in-ch cmd-rq)
                                                 (recur (inc num-ins) num-outs)))
                               (= ch out-ch) (do (render-client-hist (swap! client-hist
                                                                            #(update-in % [(:opaque v) 1]
                                                                                        conj [ts v])))
                                                 (recur num-ins (inc num-outs))))))
                  (make-fenced-pump world in-ch out-ch @max-inflight)))
              el-prefix nil)
    cmd-inject-ch))

(let [last-id (atom 0)
      gen-id #(swap! last-id inc)]
  (ago test-actx [{:gen-id gen-id :event-ch (chan (sliding-buffer 1))}]
       (println "fence-test:"
                (<! (cbfg.fence-test/test test-actx 0)))))
