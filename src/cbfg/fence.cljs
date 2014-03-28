(ns cbfg.fence
  (:require-macros [cbfg.ago :refer [achan achan-buf aclose aalts
                                     ago ago-loop aput atake atimeout]])
  (:require [cljs.core.async :refer [onto-chan]]))

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

(defn make-fenced-pump [actx in-ch out-ch max-inflight]
  (ago-loop fenced-pump actx
   [inflight-chs #{}              ; chans of requests currently being processed.
    fenced-ch nil                 ; chan of last, inflight "fenced" request.
    fenced-ch-res nil]            ; last received result from last fenced request.
   (let [i (vec (if (or fenced-ch ; if we're fenced or too many inflight requests,
                        (>= (count inflight-chs) max-inflight))
                  inflight-chs    ; then ignore in-ch & finish existing inflight requests.
                  (conj inflight-chs in-ch)))
         [v ch] (if (empty? i)    ; empty when in-ch is closed and no inflight-chs.
                  [nil nil]
                  (aalts fenced-pump i))]
     (cond
      (= nil v ch) (aclose fenced-pump out-ch)
      (= ch in-ch) (if (nil? v)
                     (recur inflight-chs out-ch nil)
                     (let [new-inflight ((:rq v) fenced-pump)]
                       (recur (conj inflight-chs new-inflight)
                              (if (:fence v) new-inflight nil)
                              nil)))
      (= v nil) (let [new-inflight-chs (disj inflight-chs ch)] ; an inflight request is done.
                  (if (empty? new-inflight-chs)                ; if inflight requests are all done,
                    (do (when fenced-ch-res                    ; finally send the fenced-ch-res that
                          (aput fenced-pump                    ; we have been holding onto.
                                out-ch fenced-ch-res))
                        (recur new-inflight-chs nil nil))
                    (recur new-inflight-chs fenced-ch fenced-ch-res)))
      (= ch fenced-ch) (do (when fenced-ch-res
                          (aput fenced-pump            ; send any previous fenced-res so
                                out-ch fenced-ch-res)) ; we can hold v as last fenced-ch-res.
                        (recur inflight-chs fenced-ch v))
      :else (do (aput fenced-pump out-ch v)
                (recur inflight-chs fenced-ch fenced-ch-res))))))

;; ------------------------------------------------------------

(defn test-add-two [actx x delay]
  (ago test-add-two actx
       (let [timeout-ch (atimeout test-add-two delay)]
         (atake test-add-two timeout-ch)
         (+ x 2))))

(defn test-range-to [actx s e delay]
  (let [out (achan actx)]
    (ago test-range-to actx
         (doseq [n (range s e)]
           (let [timeout-ch (atimeout test-range-to delay)]
             (atake test-range-to timeout-ch))
           (aput test-range-to out n))
         (aclose test-range-to out))
    out))

(defn test-helper [actx
                   in-ch-size
                   out-ch-size
                   max-inflight
                   in-msgs]
  (let [in (achan-buf actx in-ch-size)
        out (achan-buf actx out-ch-size)
        fdp (make-fenced-pump actx in out max-inflight)]
    (onto-chan in in-msgs)
    (ago-loop test-out actx [acc nil]
              (let [result (atake test-out out)]
                (if result
                  (recur (conj acc result))
                  (reverse acc))))))

(defn test [actx]
  (let [reqs [{:rq #(test-add-two % 1 200)}
              {:rq #(test-add-two % 4 100)}
              {:rq #(test-add-two % 10 50) :fence true}
              {:rq #(test-range-to % 40 45 10) :fence true}
              {:rq #(test-add-two % 9 400)}
              {:rq #(test-add-two % 9 400)}
              {:rq #(test-range-to % 0 2 100)}
              {:rq #(test-range-to % 6 10 5) :fence true}
              {:rq #(test-add-two % 30 100)}]]
    (ago test actx ; TODO - revisit timing wobbles.
         (map #(cons (if (= (sort (nth % 1))
                            (sort (nth % 2)))
                       "pass"
                       "FAIL")
                     %)
              [["test with 2 max-inflight"
                (let [test-helper-out (test-helper test 100 1 2 reqs)
                      res (atake test test-helper-out)]
                  (atake test test-helper-out)
                  res)
                '(6 3 12 40 41 42 43 44 11 11 6 7 8 0 1 9 32)]
               ["test with 200 max-inflight"
                (let [test-helper-out (test-helper test 100 1 200 reqs)
                      res (atake test test-helper-out)]
                  (atake test test-helper-out)
                  res)
                '(6 3 12 40 41 42 43 44 6 7 8 0 1 11 11 9 32)]]))))
