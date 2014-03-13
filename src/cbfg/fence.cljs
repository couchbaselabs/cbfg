(ns cbfg.fence
  (:require-macros [cljs.core.async.macros
                    :refer [go go-loop]])
  (:require [cljs.core.async
             :refer [close! <! >! <!! >!! chan timeout onto-chan]]))

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
     (close! c))
    c))

;; request format
;; {:rq function-to-call :fence true-or-false}

(defn make-rq-processor [max-inflights]
  (let [in-channel (chan 100)
        out-channel (chan)]
    (go-loop
        [inflights #{}            ; chans of requests currently being processed.
         fenced nil               ; chan of last, inflight "fenced" request.
         fenced-res nil]          ; last received result from last fenced request.
      (let [i (vec (if (or fenced ; if we're fenced or too many inflight requests,
                           (>= (count inflights) max-inflights))
                     inflights    ; then ignore in-channel & finish existing inflight requests.
                     (conj inflights in-channel)))
            [v ch] (if (empty? i) ; empty when in-channel is closed and no inflights.
                     [nil nil]
                     (alts! i))]
        (cond
         (= nil v ch) (close! out-channel)
         (= ch in-channel) (if (nil? v)
                             (recur inflights out-channel nil)
                             (let [new-inflight ((:rq v))]
                               (recur (conj inflights new-inflight)
                                      (if (:fence v) new-inflight nil)
                                      nil)))
         (= v nil) (let [new-inflights (disj inflights ch)] ; an inflight request is done.
                     (if (empty? new-inflights)
                       (do (when fenced-res                 ; all inflight requests are done, so we can
                             (>! out-channel fenced-res))   ; send the fenced-res that we've been keeping.
                           (recur new-inflights nil nil))
                       (recur new-inflights fenced fenced-res)))
         (= ch fenced) (do (when fenced-res
                             (>! out-channel fenced-res))   ; send off any previous fenced-res so
                           (recur inflights fenced v))      ; we can keep v as fenced-res.
         :else (do (>! out-channel v)
                   (recur inflights fenced fenced-res)))))
    {:in in-channel
     :out out-channel}))

(def test-requests
  [{:rq #(add-two 1 2000)}
   {:rq #(add-two 4 1000)}
   {:rq #(add-two 10 500) :fence true}
   {:rq #(range-to 40 45 100) :fence true}
   {:rq #(add-two 9 1000)}
   {:rq #(add-two 9 1000)}
   {:rq #(range-to 0 5 500)}
   {:rq #(range-to 6 10 100) :fence true}
   {:rq #(add-two 30 1000)}
   ])

(defn test-rq-processor []
  (let [{:keys [in out]} (make-rq-processor 2)
        gch (go-loop [acc nil]
              (let [result (<! out)]
                (if result
                  (do (println "Output result: " result)
                      (recur (conj acc result)))
                  (do (println "Output channel closed")
                      (reverse acc)))))]
    (onto-chan in test-requests)
    gch))
