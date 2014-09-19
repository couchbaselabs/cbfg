(ns cbfg.world.base
  (:require-macros [cljs.core.async.macros :refer [go]]
                   [cbfg.act :refer [act act-loop aclose achan aalts
                                     aput aput-close atake atimeout actx-top]])
  (:require [clojure.string :as string]
            [cljs.core.async :refer [chan <! >! close! timeout merge
                                     map< filter< sliding-buffer]]
            [ago.core :refer [make-ago-world ago-chan ago-timeout]]
            [goog.dom :as gdom]
            [cbfg.vis :refer [listen-el get-el-value
                              get-el-innerHTML set-el-innerHTML]]))

(enable-console-print!)

(defn replay-cmd-ch [cmd-inject-ch el-ids ev-to-cmd-fn]
  (merge [cmd-inject-ch
          (map< #(let [[op x] (string/split (.-id (.-target %)) #"-")]
                   {:op op :replay-to x})
                (filter< #(= "BUTTON" (.-tagName (.-target %)))
                         (listen-el (gdom/getElement "client-curr") "click")))
          (map< ev-to-cmd-fn
                (merge (map #(listen-el (gdom/getElement %) "click")
                            (conj el-ids "replay"))))]))

(defn world-replay [prev-in-ch prev-vis-chs world-vis-init el-prefix client-hist up-to-ts]
  (when (nil? up-to-ts)
    (set-el-innerHTML "client-prev" (get-el-innerHTML "client-curr")))
  (go (close! prev-in-ch)
      (doseq [prev-vis-ch (vals prev-vis-chs)]
        (close! prev-vis-ch))
      (let [cmd-ch
            (world-vis-init el-prefix
                            (js/parseInt (get-el-value (str el-prefix "-run-delay"))))]
        (doseq [[opaque [request responses]]
                (sort #(compare (first %1) (first %2)) client-hist)]
          (when (or (nil? up-to-ts)
                    (<= opaque up-to-ts))
            (>! cmd-ch (dissoc request :opaque)))))))

(defn filter-r [r] (if (map? r)
                     (-> r
                         (dissoc :opaque)
                         (dissoc :op)
                         (dissoc :fence))
                     r))

(defn render-client-hist-html [client-hist]
   (apply str
          (flatten ["<table class='hist'>"
                    "<tr><th>op</th><th>fence</th>"
                    "    <th>request</th><th>timeline</th></tr>"
                    (map (fn [[ts [request responses]]]
                           ["<tr class='hist-event"
                            (when (some #(not (:more (second %)))
                                        responses)
                              " complete") "'>"
                              " <td>" (:op request) "</td>"
                              " <td>" (:fence request) "</td>"
                              " <td>" (filter-r request) "</td>"
                              " <td class='responses'><ul>"
                              "  <li style='list-type: none; margin-left: "
                              ts "em;'>request"
                              "   <div class='timeline-focus'></div>"
                              "   <button id='replay-" ts "'>"
                              "    &lt; replay requests to here</button>"
                              "  </li>"
                              (map (fn [[response-ts response]]
                                     ["<li style='margin-left: "
                                      response-ts "em;'>"
                                      (-> (filter-r response)
                                          (dissoc :lane)
                                          (dissoc :delay)
                                          (dissoc :sleep))
                                      "</li>"])
                                   (reverse responses))
                              " </ul></td>"
                              "</tr>"])
                         (sort #(compare [(:lane (first (second %1))) (first %1)]
                                         [(:lane (first (second %2))) (first %2)])
                               client-hist))
                    "</table>"])))

(defn render-client-hist [client-hist]
  (set-el-innerHTML "client-curr" (render-client-hist-html client-hist)))

(defn world-cmd-loop [actx cmd-handlers cmd-ch req-ch res-ch
                      vis-chs world-vis-init el-prefix]
  (let [client-hist (atom {})] ; Keyed by opaque -> [request, replies].
    (act-loop cmd-loop actx [num-requests 0 num-responses 0]
              (let [[v ch] (aalts cmd-loop [cmd-ch res-ch])
                    ts (+ num-requests num-responses)]
                (when v
                  (cond
                   (= ch cmd-ch)
                   (if (= (:op v) "replay")
                     (world-replay req-ch vis-chs world-vis-init el-prefix
                                   @client-hist (:replay-to v))
                     (let [cmd (assoc-in v [:opaque] ts)
                           cmd-rq ((get cmd-handlers (:op cmd)) cmd)]
                       (render-client-hist (swap! client-hist
                                                  #(assoc % ts [cmd nil])))
                       (aput cmd-loop req-ch cmd-rq)
                       (recur (inc num-requests) num-responses)))
                   (= ch res-ch)
                   (do (render-client-hist (swap! client-hist
                                                  #(update-in % [(:opaque v) 1]
                                                              conj [ts v])))
                       (recur num-requests (inc num-responses)))))))))

;; ------------------------------------------------

(defn start-test [name test-fn]
  (let [last-id (atom 0)
        gen-id #(swap! last-id inc)
        agw (make-ago-world nil)
        get-agw (fn [] agw)]
    (act test-actx [{:gen-id gen-id
                     :get-agw get-agw
                     :event-ch (ago-chan agw -1)
                     :make-timeout-ch (fn [actx delay]
                                        (ago-timeout ((:get-agw (actx-top actx))) delay))}]
         (println (str name ":")
                  (<! (test-fn test-actx 0))))))

(defn example-add [actx c]
  (act example-add actx
       (let [timeout-ch (atimeout example-add (:delay c))]
         (atake example-add timeout-ch)
         (assoc c :value (+ (:x c) (:y c))))))

(defn example-sub [actx c]
  (act example-sub actx
       (let [timeout-ch (atimeout example-sub (:delay c))]
         (atake example-sub timeout-ch)
         (assoc c :value (- (:x c) (:y c))))))

(defn example-count [actx c]
  (let [out (achan actx)]
    (act example-count actx
         (doseq [n (range (:x c) (:y c))]
           (let [timeout-ch (atimeout example-count (:delay c))]
             (atake example-count timeout-ch))
           (aput example-count out (assoc c :value n :more true)))
         (let [timeout-ch (atimeout example-count (:delay c))]
           (atake example-count timeout-ch))
         (aput-close example-count out (assoc c :value (:y c))))
    out))
