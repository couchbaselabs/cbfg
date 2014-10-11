(ns cbfg.lane
  (:require-macros [cbfg.act :refer [act-loop achan aclose aput atake]]))

(defn make-lane-pump
  "Dispatches requests from in-ch to per-lane channels, based on an
   optional :lane key in each request.  A lane-pump invokes the
   make-lane-fn callback as needed to create a new per-lane worker
   channel, with params of [actx lane-name out-ch].  A request message
   is a map that looks like {:lane lane-name, :op OP, ...}.  A special
   OP of :lane-close will have an out-ch response with a :status field
   of either :ok or :not-found, depending if the named lane is known.
   Although a client might close a lane, there still might be inflight
   responses on the out-ch than arrive 'after' the lane closing, so an
   out-ch consumer should handle and/or drop those late responses.
   So, to avoid confusion, a client should avoid reusing lane-names."
  [actx in-ch out-ch make-lane-fn & {:keys [max-lanes]}]
  (act-loop lane-pump actx [lane-chs {} lane-chs-tot 0]
            (if-let [m (atake lane-pump in-ch)]
              (if (= (:op m) :lane-close)
                (if-let [lane-ch (get lane-chs (:lane m))]
                  (do (aclose lane-pump lane-ch)
                      (aput lane-pump out-ch (assoc m :status :ok))
                      (recur (dissoc lane-chs (:lane m)) lane-chs-tot))
                  (do (aput lane-pump out-ch (assoc m :status :not-found))
                      (recur lane-chs lane-chs-tot)))
                (if-let [lane-ch (get lane-chs (:lane m))]
                  (do (aput lane-pump lane-ch m)
                      (recur lane-chs lane-chs-tot))
                  (if (or (not max-lanes) (< (count lane-chs) max-lanes))
                    (if-let [lane-ch (make-lane-fn lane-pump (:lane m) out-ch)]
                      (do (aput lane-pump lane-ch m)
                          (recur (assoc lane-chs (:lane m) lane-ch)
                                 (inc lane-chs-tot)))
                      (do (aput lane-pump out-ch
                                (assoc m :status :failed :status-info :make-lane))
                          (recur lane-chs lane-chs-tot)))
                    (do (aput lane-pump out-ch
                              (assoc m :status :full :status-info :max-lanes))
                        (recur lane-chs lane-chs-tot)))))
              (doseq [[lane-name lane-ch] lane-chs] ; Close lane-chs & exit.
                (aclose lane-pump lane-ch)))))
