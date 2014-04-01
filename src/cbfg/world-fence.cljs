(ns cbfg.world-fence
  (:require-macros [cbfg.ago :refer [ago ago-loop aclose achan achan-buf
                                     aalts aput atake atimeout]])
  (:require [cljs.core.async :refer [chan <! merge map< sliding-buffer]]
            [goog.dom :as gdom]
            [cbfg.vis :refer [vis-init listen-el get-el-value
                              get-el-innerHTML set-el-innerHTML]]
            [cbfg.fence :refer [make-fenced-pump]]
            [cbfg.fence-test]))

(defn example-add [actx opaque-id x y delay]
  (ago example-add actx
       (let [timeout-ch (atimeout example-add delay)]
         (atake example-add timeout-ch)
         {:opaque-id opaque-id :result (+ x y)})))

(defn example-sub [actx opaque-id x y delay]
  (ago example-sub actx
       (let [timeout-ch (atimeout example-sub delay)]
         (atake example-sub timeout-ch)
         {:opaque-id opaque-id :result (- x y)})))

(defn example-count [actx opaque-id x y delay]
  (let [out (achan actx)]
    (ago example-count actx
         (doseq [n (range x y)]
           (let [timeout-ch (atimeout example-count delay)]
             (atake example-count timeout-ch))
           (aput example-count out {:opaque-id opaque-id :partial n}))
         (let [timeout-ch (atimeout example-count delay)]
           (atake example-count timeout-ch))
         (aput example-count out {:opaque-id opaque-id :result y})
         (aclose example-count out))
    out))

(def example-cmd-handlers
  {"add"   (fn [c] {:opaque-id (:opaque-id c) :fence (:fence c)
                    :rq #(example-add % (:opaque-id c) (:x c) (:y c) (:delay c))})
   "sub"   (fn [c] {:opaque-id (:opaque-id c) :fence (:fence c)
                    :rq #(example-sub % (:opaque-id c) (:x c) (:y c) (:delay c))})
   "count" (fn [c] {:opaque-id (:opaque-id c) :fence (:fence c)
                    :rq #(example-count % (:opaque-id c) (:x c) (:y c) (:delay c))})
   "test"  (fn [c] {:opaque-id (:opaque-id c) :fence (:fence c)
                    :rq #(cbfg.fence-test/test % (:opaque-id c))})})

(def example-max-inflight (atom 10))

(defn filter-r [r] (if (map? r)
                     (-> r
                         (dissoc :opaque-id)
                         (dissoc :op)
                         (dissoc :fence))
                     r))

(defn render-client-cmds [client-cmds]
  (set-el-innerHTML "client"
                    (apply str
                           (flatten ["<table>"
                                     "<tr><th>id</th><th>fence</th><th>op</th>"
                                     "    <th>args</th><th>responses</th></tr>"
                                     (map (fn [[opaque-id [request responses]]]
                                            ["<tr class='"
                                             (when (some #(:result (second %)) responses)
                                               "complete") "'>"
                                             " <td>" opaque-id "</td>"
                                             " <td>" (:fence request) "</td>"
                                             " <td>" (:op request) "</td>"
                                             " <td>" (filter-r request) "</td>"
                                             " <td class='responses'><ul>"
                                             (map (fn [[out-time response]]
                                                    ["<li style='margin-left: " out-time "em;'>"
                                                     (filter-r response)
                                                     "</li>"])
                                                  (reverse responses))
                                             "</ul></td>"
                                             "</tr>"])
                                          (sort #(compare (first %1) (first %2))
                                                client-cmds))
                                     "</table>"]))))

(defn world-vis-init [el-prefix]
  (let [cmd-ch (map< (fn [ev] {:op (.-id (.-target ev))
                               :x (js/parseInt (get-el-value "x"))
                               :y (js/parseInt (get-el-value "y"))
                               :delay (js/parseInt (get-el-value "delay"))
                               :fence (= (get-el-value "fence") "1")})
                     (merge (map #(listen-el (gdom/getElement %) "click")
                                 (keys example-cmd-handlers))))
        client-cmds (atom {})] ; Keyed by opaque-id -> [request, replies]
    (vis-init (fn [world]
                (let [in-ch (achan-buf world 100)
                      out-ch (achan-buf world 0)]
                  (ago-loop client world [num-ins 0 num-outs 0]
                            (let [[v ch] (aalts client [cmd-ch out-ch])]
                              (cond
                                (= ch cmd-ch) (let [cmd (assoc-in v [:opaque-id] num-ins)
                                                    cmd-handler ((get example-cmd-handlers (:op cmd)) cmd)]
                                                (render-client-cmds (swap! client-cmds
                                                                           #(assoc % num-ins [cmd nil])))
                                                (aput client in-ch cmd-handler)
                                                (recur (inc num-ins) num-outs))
                                (= ch out-ch) (do (render-client-cmds (swap! client-cmds
                                                                             #(update-in % [(:opaque-id v) 1]
                                                                                         conj [num-outs v])))
                                                  (recur num-ins (inc num-outs))))))
                  (make-fenced-pump world in-ch out-ch @example-max-inflight)))
              el-prefix nil)))

(let [last-id (atom 0)
      gen-id #(swap! last-id inc)]
  (ago test-actx [{:gen-id gen-id :event-ch (chan (sliding-buffer 1))}]
       (println "fence-test:"
                (<! (cbfg.fence-test/test test-actx 0)))))
