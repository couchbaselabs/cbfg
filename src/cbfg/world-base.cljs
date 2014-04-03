(ns cbfg.world-base
  (:require-macros [cljs.core.async.macros :refer [go]]
                   [cbfg.ago :refer [ago aclose achan aput atake atimeout]])
  (:require [clojure.string :as string]
            [cljs.core.async :refer [>! merge map< filter<]]
            [goog.dom :as gdom]
            [cbfg.vis :refer [listen-el set-el-innerHTML]]))

(defn replay-cmd-ch [cmd-inject-ch el-ids ev-to-cmd-fn]
  (merge [cmd-inject-ch
          (map< #(let [[op x] (string/split (.-id (.-target %)) #"-")]
                   {:op op :x x})
                (filter< #(= "BUTTON" (.-tagName (.-target %)))
                         (listen-el (gdom/getElement "client") "click")))
          (map< ev-to-cmd-fn
                (merge (map #(listen-el (gdom/getElement %) "click")
                            (conj el-ids "replay"))))]))

(defn world-replay [world-vis-init el-prefix client-hist up-to-ts]
  (let [cmd-ch (world-vis-init el-prefix)]
    (go (doseq [[opaque [request responses]]
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

(defn render-client-hist [client-hist]
  (set-el-innerHTML "client"
                    (apply str
                           (flatten ["<table class='hist'>"
                                     "<tr><th>op</th><th>fence</th>"
                                     "    <th>request</th><th>timeline</th></tr>"
                                     (map (fn [[ts [request responses]]]
                                            ["<tr class='hist-event"
                                             (when (some #(:result (second %)) responses)
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
                                                    ["<li style='margin-left: " response-ts "em;'>"
                                                     (-> (filter-r response)
                                                         (dissoc :lane)
                                                         (dissoc :delay))
                                                     "</li>"])
                                                  (reverse responses))
                                             " </ul></td>"
                                             "</tr>"])
                                          (sort #(compare [(:lane (first (second %1))) (first %1)]
                                                          [(:lane (first (second %2))) (first %2)])
                                                client-hist))
                                     "</table>"]))))

;; ------------------------------------------------

(defn example-add [actx c]
  (ago example-add actx
       (let [timeout-ch (atimeout example-add (:delay c))]
         (atake example-add timeout-ch)
         (assoc c :result (+ (:x c) (:y c))))))

(defn example-sub [actx c]
  (ago example-sub actx
       (let [timeout-ch (atimeout example-sub (:delay c))]
         (atake example-sub timeout-ch)
         (assoc c :result (- (:x c) (:y c))))))

(defn example-count [actx c]
  (let [out (achan actx)]
    (ago example-count actx
         (doseq [n (range (:x c) (:y c))]
           (let [timeout-ch (atimeout example-count (:delay c))]
             (atake example-count timeout-ch))
           (aput example-count out (assoc c :partial n)))
         (let [timeout-ch (atimeout example-count (:delay c))]
           (atake example-count timeout-ch))
         (aput example-count out (assoc c :result (:y c)))
         (aclose example-count out))
    out))
