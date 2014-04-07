(ns cbfg.net
  (:require-macros [cbfg.ago :refer [achan ago-loop aclose aalts aput]]))

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
                           ;  :to-side :to-addr :to-port
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
                     (recur (inc ts) request-listen-ch request-connect-ch
                            (assoc listens [addr port] accept-ch)
                            streams)
                     (do (aclose net accept-ch)
                         (recur (inc ts) request-listen-ch request-connect-ch listens streams))))
                 (= ch request-connect-ch)
                 (when-let [[to-addr to-port from-addr result-ch] v]
                   (if-let [accept-ch (get listens [to-addr to-port])]
                     (let [client-send-ch (achan net)
                           client-recv-ch (achan net)
                           server-send-ch (achan net)
                           server-recv-ch (achan net)]
                       ; TODO: Need to model connection delay and running out of ports.
                       (aput net result-ch [client-send-ch client-recv-ch])
                       (aput net accept-ch [server-send-ch server-recv-ch])
                       (recur (inc ts) request-listen-ch request-connect-ch listens
                              (-> streams
                                  (assoc client-send-ch {:send-ch client-send-ch :recv-ch server-recv-ch
                                                         :to-side :recv :to-addr to-addr :to-port to-port})
                                  (assoc server-send-ch {:send-ch server-send-ch :recv-ch client-recv-ch
                                                         :to-side :send :to-addr to-addr :to-port to-port}))))
                     (do (aclose net result-ch)
                         (recur (inc ts) request-listen-ch request-connect-ch listens streams))))
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

