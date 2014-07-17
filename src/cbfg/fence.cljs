(ns cbfg.fence
  (:require-macros [cbfg.act :refer [aclose aalts act-loop aput]]))

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
;;                        ; processing == 0
;;   v                    ; ++processing == 1
;;      v                 ; ++processing == 2
;;      p1                ; send p1 result
;;         v              ; ++processing == 3, and...
;;         ||             ; pause input processing
;;      p1                ; send p1 result
;;      d1                ; send d1, --processing == 2
;;         p2             ; send p2 result
;;         d2-hold        ; hold d2 result, --processing == 1
;;   p0                   ; send p0 result
;;   d0                   ; send d0, --processing == 0, so...
;;         d2-send        ; can now send the held d2, and...
;;         >>             ; unpause input processing
;;            v           ; ++processing == 1
;;               v        ; ++processing == 2

;; ------------------------------------------------------------

;; request format
;; {:rq function-to-call :fence true-or-false}

(defn make-fenced-pump [actx name in-ch out-ch max-inflight close?]
  (act-loop fenced-pump actx
   [name name
    inflight-chs #{}                ; chans of requests currently being processed.
    fenced-ch nil                   ; chan of last, inflight "fenced" request.
    fenced-ch-res nil]              ; last received result from last fenced request.
   (let [chs (vec (if (or fenced-ch ; if we're fenced or too many inflight requests,
                          (>= (count inflight-chs) max-inflight))
                    inflight-chs    ; then ignore in-ch & finish existing inflight requests.
                    (conj inflight-chs in-ch)))]
     (if (seq chs)                  ; empty when in-ch is closed and no inflight-chs.
       (let [[v ch] (aalts fenced-pump chs)]
         (cond
          (= ch in-ch) (if (nil? v)
                         (recur name inflight-chs out-ch nil)      ; use out-ch as sentinel.
                         (let [new-inflight ((:rq v) fenced-pump)]
                           (recur name (conj inflight-chs new-inflight)
                                  (when (:fence v) new-inflight)
                                  nil)))
          (= v nil) (let [new-inflight-chs (disj inflight-chs ch)] ; inflight request done.
                      (if (empty? new-inflight-chs)           ; all inflight requests done,
                        (do (when fenced-ch-res               ; so send held fenced-ch-res.
                              (aput fenced-pump out-ch fenced-ch-res))
                            (recur name new-inflight-chs nil nil))
                        (recur name new-inflight-chs fenced-ch fenced-ch-res)))
          (= ch fenced-ch) (do (when fenced-ch-res ; send held fenced-res to hold onto v.
                                 (aput fenced-pump out-ch fenced-ch-res))
                               (recur name inflight-chs fenced-ch v))
          :else (do (aput fenced-pump out-ch v)
                    (recur name inflight-chs fenced-ch fenced-ch-res))))
       (when close? (aclose fenced-pump out-ch))))))
