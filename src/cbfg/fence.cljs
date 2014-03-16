(ns cbfg.fence
  (:require-macros [cbfg.ago :refer [achan achan-buf aclose aalts
                                     ago ago-loop aput atake]])
  (:require [cljs.core.async :refer [<! timeout onto-chan]]))

;; Explaining out-of-order replies and fencing with a diagram.  Client
;; sends a bunch of requests (r0...r4), where r2 is fenced (F).  "pX"
;; denotes a partial "still going / non-final" response message.  "dX"
;; denotes a final response "done" message for a request.  Time-steps
;; go downwards.  The caret (^) denotes which request has started
;; async or inflight processing.  The double-bar (||) means we have
;; paused moving the caret rightwards, so input request processing is
;; paused (in-channel is ignored).
;;
;;   r0 r1 r2 r3 r4
;;         F
;;   -----------------------------------------------
;;                        ; inflight == 0
;;   ^                    ; ++inflight == 1
;;      ^                 ; ++inflight == 2
;;      p1                ; send p1 result
;;         ^              ; ++inflight == 3, and...
;;         ||             ; pause input processing
;;      p1                ; send p1 result
;;      d1                ; send d1, --inflight == 2
;;         p2             ; send p2 result
;;         d2-hold        ; hold d2 result, --inflight == 1
;;   p0                   ; send p0 result
;;   d0                   ; send d0, --inflight == 0, so...
;;         d2-send        ; can now send the held d2, and...
;;         >>             ; unpause input processing
;;            ^           ; ++inflight == 1
;;               ^        ; ++inflight == 2

;; ------------------------------------------------------------

;; request format
;; {:rq function-to-call :fence true-or-false}

(defn make-fenced-pump [actx in-ch out-ch max-inflights]
  (ago-loop fenced-pump actx
   [inflights #{}              ; chans of requests currently being processed.
    fenced nil                 ; chan of last, inflight "fenced" request.
    fenced-res nil]            ; last received result from last fenced request.
   (let [i (vec (if (or fenced ; if we're fenced or too many inflight requests,
                        (>= (count inflights) max-inflights))
                  inflights    ; then ignore in-ch & finish existing inflight requests.
                  (conj inflights in-ch)))
         [v ch] (if (empty? i) ; empty when in-ch is closed and no inflights.
                  [nil nil]
                  (aalts fenced-pump i))]
     (cond
      (= nil v ch) (aclose fenced-pump out-ch)
      (= ch in-ch) (if (nil? v)
                     (recur inflights out-ch nil)
                     (let [new-inflight ((:rq v) actx)]
                       (recur (conj inflights new-inflight)
                              (if (:fence v) new-inflight nil)
                              nil)))
      (= v nil) (let [new-inflights (disj inflights ch)] ; an inflight request is done.
                  (if (empty? new-inflights)             ; if inflight requests are all done,
                    (do (when fenced-res                 ; finally send the fenced-res that
                          (aput fenced-pump              ; we have been holding onto.
                                out-ch fenced-res))
                        (recur new-inflights nil nil))
                    (recur new-inflights fenced fenced-res)))
      (= ch fenced) (do (when fenced-res
                          (aput fenced-pump              ; send any previous fenced-res so
                                out-ch fenced-res))      ; we can hold v as last fenced-res.
                        (recur inflights fenced v))
      :else (do (aput fenced-pump out-ch v)
                (recur inflights fenced fenced-res))))))

;; ------------------------------------------------------------

(defn add-two [actx x delay]
  (ago test-add-two actx
       (<! (timeout delay))
       (+ x 2)))

(defn range-to [actx s e delay]
  (let [c (achan actx)]
    (ago test-range-to actx
         (doseq [n (range s e)]
           (<! (timeout delay))
           (aput test-range-to c n))
         (aclose test-range-to c))
    c))

(defn test-helper [actx
                   in-ch-size
                   out-ch-size
                   max-inflight
                   in-msgs]
  (let [in (achan-buf actx in-ch-size)
        out (achan-buf actx out-ch-size)
        fdp (make-fenced-pump actx in out max-inflight)
        gch (ago-loop test-helper actx [acc nil]
              (let [result (atake test-helper out)]
                (if result
                  (do (println "Output result: " result)
                      (recur (conj acc result)))
                  (do (println "Output channel closed")
                      (reverse acc)))))]
    (onto-chan in in-msgs)
    gch))

(defn test [actx]
  (let [reqs [{:rq #(add-two % 1 200)}
              {:rq #(add-two % 4 100)}
              {:rq #(add-two % 10 50) :fence true}
              {:rq #(range-to % 40 45 10) :fence true}
              {:rq #(add-two % 9 400)}
              {:rq #(add-two % 9 400)}
              {:rq #(range-to % 0 2 100)}
              {:rq #(range-to % 6 10 5) :fence true}
              {:rq #(add-two % 30 100)}]]
    (ago test actx
         (map (fn [test] (cons (if (= (sort (nth test 1)) ; TODO - revisit timing wobbles.
                                      (sort (nth test 2)))
                                 "pass"
                                 "FAIL")
                               test))
              [["test with 2 max-inflights"
                (atake test (test-helper actx 100 1 2 reqs))
                '(6 3 12 40 41 42 43 44 11 11 6 7 8 0 1 9 32)]
               ["test with 200 max-inflights"
                (atake test (test-helper actx 100 1 200 reqs))
                '(6 3 12 40 41 42 43 44 6 7 8 0 1 11 11 9 32)]]))))
