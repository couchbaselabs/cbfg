(ns cbfg.net
  (:require-macros [cbfg.ago :refer [achan-buf ago-loop aclose aalts aput aput-close]]))

(defn make-net [actx request-listen-ch request-connect-ch & opts]
  (let [clean-up #()]
    (ago-loop net actx
              [ts 0
               listens {}  ; Keyed by [addr port], value is accept-ch.
               streams {}  ; A conn is represented by dual streams, where streams
                           ; is keyed by send-ch and a value of...
                           ; {:send-addr :send-port :send-ch
                           ;  :recv-addr :recv-port :recv-ch
                           ;  :side (:client|:server)
                           ;  :server-addr :server-port
                           ;  :msgs [[deliver-at msg] ...]}.
               results []] ; A result is [ch msg].
              (let [deliverables (mapcat (fn [send-ch stream]
                                         (when-let [[deliver-at msg] (first (:msgs stream))]
                                           (when (>= ts deliver-at)
                                             [[(:recv-ch stream) (first (:msgs stream))]])))
                                       streams)
                    sendables (mapcat (fn [send-ch stream]
                                       (when (< (count (:msgs stream))
                                                (get opts :max-msgs-per-stream 10))
                                         [(:send-ch stream)]))
                                      streams)
                    [v ch] (aalts net (-> [request-listen-ch request-connect-ch]
                                          (into deliverables)
                                          (into sendables)
                                          (into results)))]
                (cond
                 (= ch request-listen-ch)
                 (if-let [[addr port accept-ch] v]
                   (if (get listens [addr port])
                     (recur (inc ts)
                            (assoc listens [addr port] accept-ch)
                            streams
                            results)
                     (do (aclose net accept-ch)
                         (recur (inc ts) listens streams results)))
                   (doseq [[[addr port] accept-ch] listens]
                     (aclose net accept-ch)))
                 (= ch request-connect-ch)
                 (if-let [[to-addr to-port from-addr result-ch] v]
                   ; TODO: Need to model connection delay, perhaps with a generic "later".
                   (if-let [accept-ch (get listens [to-addr to-port])]
                     (let [end-point-buf-size (get opts :end-point-buf-size 0)
                           client-send-ch (achan-buf net end-point-buf-size)
                           client-recv-ch (achan-buf net end-point-buf-size)
                           server-send-ch (achan-buf net end-point-buf-size)
                           server-recv-ch (achan-buf net end-point-buf-size)]
                       ; TODO: Need to model running out of ports.
                       ; TODO: Need to model stop accepting connections / stop listening.
                       (recur (inc ts)
                              listens
                              (-> streams
                                  (assoc client-send-ch {:send-ch client-send-ch :recv-ch server-recv-ch
                                                         :server-addr to-addr :server-port to-port
                                                         :side :client :msgs []})
                                  (assoc server-send-ch {:send-ch server-send-ch :recv-ch client-recv-ch
                                                         :server-addr to-addr :server-port to-port
                                                         :side :server :msgs []}))
                              (-> results
                                  (conj [result-ch [client-send-ch client-recv-ch]])
                                  (conj [accept-ch [server-send-ch server-recv-ch]]))))
                     (do (aclose net result-ch)
                         (recur (inc ts) listens streams results)))
                   (doseq [[[addr port] accept-ch] listens]
                     (aclose net accept-ch)))
                 (contains? sendables ch)
                 (let [send-ch ch]
                   (if-let [stream (get streams send-ch)]
                     (let [[msg result-ch] v]
                       (recur (inc ts)
                              listens
                              (update-in streams [send-ch :msgs]
                                         conj [(+ ts (get :opts :delivery-delay 0)) msg])
                              (if result-ch
                                (conj results [result-ch :ok])
                                result-ch)))
                     (recur (inc ts)
                            listens
                            (update-in streams [send-ch :send-closed] true)
                            results)))
                 :else ; Must be a completed deliverable (recv-ch aput'ed) or completed results aput.
                 (if-let [stream (first (filter #(= (:recv-ch %) ch) (vals streams)))]
                   (let [stream2 (update-in stream [:msgs] rest)]
                     (if (and (:send-closed stream2)
                              (empty? (:msgs stream2)))
                       (do (aclose net (:recv-ch stream2))
                           (recur (inc ts)
                                  listens
                                  (dissoc streams (:send-ch stream2))
                                  results))
                       (recur (inc ts)
                              listens
                              (assoc streams (:send-ch stream2) stream2)
                              results)))
                   (recur (inc ts)
                          listens
                          streams
                          (remove #(= (first %) ch) results))))))))
