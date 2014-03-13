(ns cbfg.fence
  (:require [clojure.core.async :refer [go go-loop >! <! >!! <!! chan timeout]]))

(defn add-two [x delay]
  (go
   (<! (timeout delay))
   (+ x 2)))

;; request format
;; {:rq function-to-call :fence true-or-false}

(defn make-rq-processor []
  (let [input-channel (chan 100)
        output-channel (chan)]
    (go-loop
     [receiving #{}
      fenced-on nil
      fenced-value nil]
     (let [[v ch] (alts! (vec (into receiving
                                    (if fenced-on
                                      []
                                      [input-channel]))))]
       (cond
        (= ch input-channel) (recur (conj receiving ((:rq v)))
                                    (if (:fence v) v nil)
                                    nil)
        :else (cond
               (= v nil) (let [new-receiving (disj receiving ch)]
                           (if (empty? new-receiving)
                             (do (if fenced-value (>! output-channel fenced-value))
                                 (recur new-receiving nil nil))
                             (recur new-receiving fenced-on fenced-value)))
               :else (cond
                      (= ch fenced-on) (do (if fenced-value
                                             (>! output-channel fenced-value))
                                           (recur receiving fenced-on v))
                      :else (do (>! output-channel v)
                                (recur receiving fenced-on fenced-value)))))))
    {:in input-channel
     :out output-channel}))

(def test-requests
  [{:rq #(add-two 1 3000)}
   {:rq #(add-two 4 1000)}
   {:rq #(add-two 10 6000) :fence true}
   {:rq #(add-two 9 1000)}
   {:rq #(add-two 9 1000)}
   {:rq #(add-two 9 1000)}
   ])

(defn test-rq-processor []
  (let [{:keys [in out]} (make-rq-processor)]
    (go-loop [] (let [result (<! out)]
                  (when result
                    (println "Got result: " result)
                    (recur))))
    (doseq [rq test-requests]
      (>!! in rq))))

