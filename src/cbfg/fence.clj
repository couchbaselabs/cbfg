(ns cbfg.fence
  (:require [clojure.core.async :refer [go go-loop close! >! <! >!! <!! chan timeout
                                        onto-chan]]))

(defn add-two [x delay]
  (go
   (<! (timeout delay))
   (+ x 2)))

(defn range-to [s e delay]
  (let [c (chan)]
    (go
     (doseq [n (range s e)]
       (<! (timeout delay))
       (>! c n))
     (close! c))))

;; request format
;; {:rq function-to-call :fence true-or-false}

(defn make-rq-processor []
  (let [input-channel (chan 100)
        output-channel (chan)]
    (go-loop
     [receiving #{}
      fenced-on nil
      fenced-value nil]
     (let [ports (vec receiving)
           ports (if fenced-on ports (conj ports input-channel))
           [v ch] (if-not (empty? ports) (alts! ports) [nil nil])]
       (cond
        (= nil v ch) (close! output-channel)
        (= ch input-channel) (if (nil? v)
                               (recur receiving output-channel nil)
                               (recur (conj receiving ((:rq v)))
                                      (if (:fence v) ch nil)
                                      nil))
        :else (if (= v nil)
                (let [new-receiving (disj receiving ch)]
                  (if (empty? new-receiving)
                    (do (if fenced-value
                          (>! output-channel fenced-value))
                        (recur new-receiving nil nil))
                    (recur new-receiving fenced-on fenced-value)))
               (if (= ch fenced-on)
                 (do (if fenced-value
                       (>! output-channel fenced-value))
                     (recur receiving fenced-on v))
                 (do (>! output-channel v)
                     (recur receiving fenced-on fenced-value)))))))
    {:in input-channel
     :out output-channel}))

(def test-requests
  [{:rq #(add-two 1 3000)}
   {:rq #(add-two 4 1000)}
   {:rq #(add-two 10 6000) :fence true}
   {:rq #(add-two 9 1000)}
   {:rq #(add-two 9 1000)}
   {:rq #(range-to 0 5 500)}
   {:rq #(range-to 6 10 1000) :fence true}
   {:rq #(add-two 30 1000)}
   ])

(defn test-rq-processor []
  (let [{:keys [in out]} (make-rq-processor)
        done (go-loop [] (let [result (<! out)]
                  (when result
                    (println "Got result: " result)
                    (recur))))]
    (onto-chan in test-requests)
    (<!! done)
    (println "Output channel closed")))


