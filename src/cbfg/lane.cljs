(ns cbfg.lane
  (:require-macros [cbfg.ago :refer [ago-loop achan aclose aput atake]]))

(defn make-lane-pump [actx in-ch out-ch make-lane-fn]
  "Dispatches messages from in-ch to per-lane channels, based on an
   optional :lane key in each message.  The make-lane-fn is used to
   create a new per-lane worker channel for any as-yet unseen lane
   name, and is invoked with parameters of [actx lane-name out-ch].  A
   message is an associative map that looks like {:lane lane-name, :op
   OP, ...}, where the :lane and :op keys are optional.  A special OP
   of :close-lane will result in an out-ch response message that
   includes a :status field of either :ok or :not-found, depending if
   the named lane is known.  Although a client might close a lane,
   there still might be inflight messages on the out-ch than arrive
   'after' the lane closing, so an out-ch consumer should be prepared
   to handle and/or drop those late messages.  To avoid confusion due
   to this, a client should also avoid reusing lane names."
  (ago-loop lane-pump actx [lane-chs {}]
            (if-let [m (atake lane-pump in-ch)]
              (if (= (:op m) :close-lane)
                (if-let [lane-ch (get lane-chs (:lane m))]
                  (do (aclose lane-pump lane-ch)
                      (aput lane-pump out-ch (assoc m :status :ok))
                      (recur (dissoc lane-chs (:lane m))))
                  (do (aput lane-pump out-ch (assoc m :status :not-found))
                      (recur lane-chs)))
                (if-let [lane-ch (get lane-chs (:lane m))]
                  (do (aput lane-pump lane-ch m)
                      (recur lane-chs))
                  (let [lane-ch (make-lane-fn lane-pump (:lane m) out-ch)]
                    (aput lane-pump lane-ch m)
                    (recur (assoc lane-chs (:lane m) lane-ch)))))
              (doseq [[lane-name lane-ch] lane-chs] ; Close lane-chs & exit.
                (aclose lane-pump lane-ch)))))