(ns cbfg.fence
  (:require-macros [cljs.core.async.macros
                    :refer [go go-loop]])
  (:require [cljs.core.async
             :refer [close! <! >! <!! >!! chan timeout onto-chan]]))

;; Explaining out-of-order replies and fencing with a diagram.  Client
;; sends a bunch of requests (r0...r6), where r2 and r5 are fenced
;; ("F").  "pX" means a partial "still going / non-final" response
;; message.  "dX" means a final response "done" message for a request.
;; Time-steps go downwards.  The caret (^) denotes which request has
;; started async or inflight processing.  The double-bar (||) means we
;; have paused moving the caret rightwards, so input request
;; processing is paused (in-channel is ignored).
;;
;;   r0 r1 r2 r3 r4 r5 r6
;;         F        F
;;   -----------------------------------------------
;;   ^                    (++inflight == 1)
;;      ^                 (++inflight == 2)
;;      p1                (send)
;;         ^              (++inflight == 3 (2 unfenced + 1 fenced), and...)
;;         ||             (pause input processing)
;;      p1                (send)
;;      d1                (send, --inflight == 2)
;;         p2             (send)
;;         d2-hold        (hold d2 result, --inflight == 1)
;;   p0                   (send)
;;   d0                   (send, --inflight == 0, so...)
;;         d2-send        (now can send d2 and move onwards to r3, so...)
;;         >>             (unpause input processing)
;;            ^           (++inflight == 1)
;;               ^        (++inflight == 2)

;; ------------------------------------------------------------

;; request format
;; {:rq function-to-call :fence true-or-false}

(defn make-fenced-dispatcher [in-channel out-channel max-inflights]
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
                 (recur inflights fenced fenced-res))))))

;; ------------------------------------------------------------

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

(defn test []
  (let [reqs [{:rq #(add-two 1 200)}
              {:rq #(add-two 4 100)}
              {:rq #(add-two 10 50) :fence true}
              {:rq #(range-to 40 45 10) :fence true}
              {:rq #(add-two 9 100)}
              {:rq #(add-two 9 100)}
              {:rq #(range-to 0 5 50)}
              {:rq #(range-to 6 10 10) :fence true}
              {:rq #(add-two 30 100)}]]
    (go (map (fn [test] (cons (if (= (nth test 1)
                                     (nth test 2))
                                "pass"
                                "FAIL")
                              test))
             [["test with 2 max-inflights"
               (<! (test-helper 100 1 2 reqs))
               '(6 3 12 40 41 42 43 44 11 11 6 7 0 8 1 2 3 4 9 32)]
              ["test with 200 max-inflights"
               (<! (test-helper 100 1 200 reqs))
               '(6 3 12 40 41 42 43 44 6 7 0 8 11 11 1 2 3 4 9 32)]]))))

(defn test-helper [in-ch-size
                   out-ch-size
                   max-inflight
                   in-msgs]
  (let [in (chan in-ch-size)
        out (chan out-ch-size)
        fdp (make-fenced-dispatcher in out max-inflight)
        gch (go-loop [acc nil]
              (let [result (<! out)]
                (if result
                  (do (println "Output result: " result)
                      (recur (conj acc result)))
                  (do (println "Output channel closed")
                      (reverse acc)))))]
    (onto-chan in in-msgs)
    gch))
