(ns cbfg.grouper
  (:require-macros [cbfg.act :refer [act-loop achan aclose aalts]]))

(defn make-grouper [actx put-ch take-all-ch max-entries-per-take]
  "A grouper manages a put-ch, a take-all-ch, and a set of entries.
   Multiple producers can put entries to the put-ch, and a consumer
   can grab all the collected entries in one shot by a take on the
   take-all-ch.  During a put, an entry is a [key update-fn], so
   producers can merge or group entries, such as for de-dupe or
   piggy-backing requests.  Puts will block if there are more than
   max-entries-per-take number of entries.  During a take-all, the received
   entries is an associative map of key to update-fn-result.
   The grouper closes the take-all-ch after put-ch is closed
   and all the entries are taken."
  (act-loop grouper actx
            [entries {}
             max-entries-per-take max-entries-per-take
             tot-puts 0
             tot-takes 0
             tot-entries 0] ; Can be < tot-puts if there's de-deupe.
            (let [n (count entries)
                  chs0 (if (>= n max-entries-per-take)
                         []        ; We're full, so ignore put'ers.
                         [put-ch]) ; We still have room, so accept puts.
                  chs1 (if (> n 0) ; We've entries, so try to fulfill takers.
                         (conj chs0 [take-all-ch entries])
                         chs0)]    ; We're empty, so ignore takers.
              (if (seq chs1)
                (let [[m ch] (aalts grouper chs1)]
                  (if (= ch put-ch)
                    (if-let [[k f] m]
                      (recur (update-in entries [k] f) max-entries-per-take
                             (inc tot-puts) tot-takes tot-entries)
                      (recur entries -1 tot-puts tot-takes tot-entries))
                    (recur {} max-entries-per-take
                           tot-puts (inc tot-takes) (+ tot-entries n))))
                (aclose grouper take-all-ch)))))

