(ns cbfg.net
  (:require-macros [cbfg.ago :refer [achan-buf ago ago-loop aalts
                                     aput aput-close atake]]))

(defn make-net [actx request-listen-ch request-connect-ch max-msgs-per-stream]
  (let [clean-up #()]
    (ago-loop net actx
              [ts 0
               request-listen-ch request-listen-ch
               request-connect-ch request-connect-ch
               listens {} ; Keyed by [addr port], value is accept-ch.
               streams {}] ; A conn is represented by dual streams, where streams
                           ; is keyed by send-ch and a value of...
                           ; {:send-addr :send-port :send-ch
                           ;  :recv-addr :recv-port :recv-ch
                           ;  :accept-addr :accept-port
                           ;  :msgs :tot-msgs}.
              (let [deliveries (mapcat (fn [send-ch stream]
                                         (when-let [[deliver-at msg] (first (:msgs stream))]
                                           (when (>= ts deliver-at)
                                             [[(:recv-ch stream) (first (:msgs stream))]])))
                                       streams)
                    sendables (mapcat (fn [send-ch stream]
                                       (when (< (count (:msgs stream)) max-msgs-per-stream)
                                         [(:send-ch stream)]))
                                      streams)
                    listenables (vals listens) ; TODO: Handle too many ports case.
                    [v ch] (aalts net (vec (concat [request-listen-ch request-connect-ch]
                                                   deliveries sendables listenables)))]
                (cond
                 (= ch request-listen-ch)
                 (when-let [[addr port accept-ch] v]
                   (if (get listens [addr port])
                     (recur (inc ts) request-listen-ch request-connect-ch listens streams)
                     (recur (inc ts) request-listen-ch request-connect-ch listens streams)))
                 (= ch request-connect-ch)
                 (when v
                   (recur (inc ts) request-listen-ch request-connect-ch listens streams))
                 (contains? listenables ch)
                 (if v
                   (recur (inc ts) request-listen-ch request-connect-ch listens streams)
                   (recur (inc ts) request-listen-ch request-connect-ch listens streams))
                 (contains? sendables ch)
                 (if v
                   (recur (inc ts) request-listen-ch request-connect-ch listens streams)
                   (recur (inc ts) request-listen-ch request-connect-ch listens streams))
                 :else ; Must be a deliverable.
                 (recur (inc ts) request-listen-ch request-connect-ch listens streams))))))

