(ns cbfg.net
  (:require-macros [cbfg.ago :refer [achan-buf ago-loop aclose aalts aput aput-close]]))

(defn make-net [actx request-listen-ch request-connect-ch
                max-msgs-per-stream delivery-delay end-point-buf-size]
  (let [clean-up #()]
    (ago-loop net actx
              [ts 0
               listens {}  ; Keyed by [addr port], value is accept-ch.
               streams {}  ; A conn is represented by dual streams, where streams
                           ; is keyed by send-ch and a value of...
                           ; {:send-addr :send-port :send-ch
                           ;  :recv-addr :recv-port :recv-ch
                           ;  :to-side :to-addr :to-port
                           ;  :msgs [[deliver-at msg] ...]}.
               results []] ; A result is [ch msg].
              (let [deliverables (mapcat (fn [send-ch stream]
                                         (when-let [[deliver-at msg] (first (:msgs stream))]
                                           (when (>= ts deliver-at)
                                             [[(:recv-ch stream) (first (:msgs stream))]])))
                                       streams)
                    sendables (mapcat (fn [send-ch stream]
                                       (when (< (count (:msgs stream)) max-msgs-per-stream)
                                         [(:send-ch stream)]))
                                      streams)
                    [v ch] (aalts net (-> [request-listen-ch request-connect-ch]
                                          (into deliverables)
                                          (into sendables)
                                          (into results)))]
                (cond
                 (= ch request-listen-ch)
                 (when-let [[addr port accept-ch] v]
                   (if (get listens [addr port])
                     (recur (inc ts)
                            (assoc listens [addr port] accept-ch)
                            streams
                            results)
                     (do (aclose net accept-ch)
                         (recur (inc ts) listens streams results))))
                 (= ch request-connect-ch)
                 (when-let [[to-addr to-port from-addr result-ch] v]
                   ; TODO: Need to model connection delay, perhaps with a generic "later".
                   (if-let [accept-ch (get listens [to-addr to-port])]
                     (let [client-send-ch (achan-buf net end-point-buf-size)
                           client-recv-ch (achan-buf net end-point-buf-size)
                           server-send-ch (achan-buf net end-point-buf-size)
                           server-recv-ch (achan-buf net end-point-buf-size)]
                       ; TODO: Need to model running out of ports.
                       ; TODO: Need to model stop accepting connections / stop listening.
                       (recur (inc ts)
                              listens
                              (-> streams
                                  (assoc client-send-ch {:send-ch client-send-ch :recv-ch server-recv-ch
                                                         :to-side :recv :to-addr to-addr :to-port to-port
                                                         :msgs []})
                                  (assoc server-send-ch {:send-ch server-send-ch :recv-ch client-recv-ch
                                                         :to-side :send :to-addr to-addr :to-port to-port
                                                         :msgs []}))
                              (-> results
                                  (conj [result-ch [client-send-ch client-recv-ch]])
                                  (conj [accept-ch [server-send-ch server-recv-ch]]))))
                     (do (aclose net result-ch)
                         (recur (inc ts) listens streams results))))
                 (contains? sendables ch)
                 (let [send-ch ch]
                   (if-let [stream (get streams send-ch)]
                     (let [[msg result-ch] v]
                       (recur (inc ts)
                              listens
                              (update-in streams [send-ch :msgs] conj [(+ ts delivery-delay) msg])
                              (if result-ch
                                (conj results [result-ch :ok])
                                result-ch)))
                     (recur (inc ts)
                            listens
                            (dissoc streams send-ch) ; TODO: Need to model close better by draining then closing.
                            results)))
                 :else ; Must be completed deliverable (recv-ch aput'ed) or completed results aput.
                 (let [stream (first (filter #(= (:recv-ch %) ch) (vals streams)))]
                   ; TODO: Need to remove first msg from msgs.
                   ; TODO: Handle closing recv-ch.
                   (recur (inc ts)
                          listens
                          (dissoc streams (:send-ch stream))
                          (remove #(= (first %) ch) results))))))))

