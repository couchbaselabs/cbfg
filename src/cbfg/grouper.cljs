(ns cbfg.grouper
  (:require-macros [cbfg.ago :refer [ago-loop achan aclose aalts]]))

(defn make-grouper [actx put-ch take-all-ch max-entries]
  "A grouper manages a put-ch, a take-all-ch, and a set of entries.
   Multiple producers can put entries to the put-ch, and a consumer
   can grab all the collected entries in one shot by a take on the
   take-all-ch.  During a put, an entry is a [key update-fn], so
   producers can merge or group entries, such as for de-dupe or
   piggy-backing requests.  Puts will block if there are more than
   max-entries number of entries.  During a take-all, the received
   entries is an associative map of key to update-fn-result."
  (ago-loop grouper actx
            [entries {}
             max-entries max-entries]
            (let [n (count entries)
                  a0 (if (>= n max-entries)
                       []        ; We're full, so ignore put'ers.
                       [put-ch]) ; We still have room, so accept puts.
                  a1 (if (> n 0) ; We've entries, so try to fulfill takers.
                       (conj a0 [take-all-ch entries])
                       a0)       ; We're empty, so ignore takers.
                  [m ch] (aalts grouper a1)]
              (cond
               (= ch put-ch) (if-let [[k f] m]
                               (recur (update-in entries [k] f) max-entries)
                               (recur entries -1))
               (= ch take-all-ch) (if m
                                    (recur {} max-entries)
                                    (aclose grouper put-ch))))))

