(ns cbfg.lane
  (:require-macros [cbfg.ago :refer [ago-loop achan aput atake]]))

(defn make-lane-pump [actx in-ch out-ch make-lane]
  (ago-loop lane-pump actx [lane-chs {}]
            (if-let [m (atake lane-pump in-ch)]
              (if (= (:op m) :close-lane)
                (if-let [lane-ch (get lane-chs (:lane m))]
                  (do (aclose lane-pump lane-ch)
                      ; Although we closed the lane-ch, the lane might
                      ; still be putting late responses on the out-ch,
                      ; which the client should be prepared to handle
                      ; or drop and to also not re-use lane names.
                      (aput lane-pump out-ch (merge m {:status ok}))
                      (recur (dissoc lane-chs (:lane m))))
                  (do (aput lane-pump out-ch (merge m {:status not-found})) ; Unknown lane.
                      (recur lane-chs)))
                (let [[lane-ch lane-chs2] (if-let [lane-ch (get lane-chs (:lane m))]
                                            [lane-ch lane-chs]
                                            (let [new-lane-ch (make-lane lane-pump (:lane m))]
                                              [new-lane-ch (assoc lane-chs (:lane m)
                                                                  new-lane-ch)]))]
                  (aput lane-pump lane-ch m)
                  (recur lane-chs2))))))
