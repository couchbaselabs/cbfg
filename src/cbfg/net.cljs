(ns cbfg.net
  (:require-macros [cbfg.ago :refer [achan achan-buf ago ago-loop aclose aalts aput]]))

(defn net-clean-up [actx listens streams results]
  (ago cleanup actx
       (doseq [[[addr port] [accept-close-ch accept-ch]] listens]
         (aclose cleanup accept-ch))
       (doseq [stream (vals streams)]
         (when (:recv-ch stream)
           (aclose cleanup (:recv-ch stream))))
       (doseq [[result-ch msg] results]
         (aclose cleanup result-ch)))
  :done)

(defn make-net [actx request-listen-ch request-connect-ch & opts]
  (ago-loop net actx
            [ts 0        ; A increasing event / time stamp counter.
             listens {}  ; Keyed by [addr port], value is [close-accept-ch accept-ch].
             streams {}  ; A conn is represented by dual streams, where streams
                         ; is keyed by send-ch and a value of...
                         ; {:send-addr :send-port :send-ch
                         ;  :recv-addr :recv-port :recv-ch
                         ;  :side (:client|:server)
                         ;  :server-addr :server-port
                         ;  :msgs [[deliver-at msg] ...]}.
             results []] ; A result entry is [result-ch msg].
            (let [close-accept-chs (map first (vals listens))
                  close-recv-chs (mapcat (fn [[send-ch stream]]
                                           (when-let [close-recv-ch (:close-recv-ch stream)]
                                             [close-recv-ch]))
                                         streams)
                  deliverables (mapcat (fn [[send-ch stream]]
                                         (when-let [[deliver-at msg] (first (:msgs stream))]
                                           (when (and (:recv-ch stream)
                                                      (>= ts deliver-at))
                                             [[(:recv-ch stream) msg]])))
                                       streams)
                  send-chs (mapcat (fn [[send-ch stream]]
                                     (when (and (not (:send-closed stream))
                                                (< (count (:msgs stream))
                                                   (get opts :max-msgs-per-stream 10)))
                                       [send-ch]))
                                   streams)
                  chs (-> [request-listen-ch request-connect-ch]
                          (into close-accept-chs)
                          (into close-recv-chs)
                          (into deliverables)
                          (into send-chs)
                          (into results))
                  [v ch] (aalts net chs)]
              (cond
               (= ch request-listen-ch)
               (if-let [[addr port result-ch] v]
                 (if (get listens [addr port])
                   (do (aclose net result-ch)
                       (recur (inc ts) listens streams results))
                   (let [accept-chs [(achan net) (achan net)]]
                     (recur (inc ts)
                            (assoc listens [addr port] accept-chs)
                            streams
                            (conj results [result-ch accept-chs]))))
                 (net-clean-up net listens streams results))
               (= ch request-connect-ch)
               (if-let [[to-addr to-port from-addr result-ch] v]
                 ; TODO: Need to model connection delay, perhaps with a generic "later".
                 (if-let [[close-accept-ch accept-ch] (get listens [to-addr to-port])]
                   (let [end-point-buf-size (get opts :end-point-buf-size 0)
                         client-send-ch (achan-buf net end-point-buf-size)
                         client-recv-ch (achan-buf net end-point-buf-size)
                         close-client-recv-ch (achan net)
                         server-send-ch (achan-buf net end-point-buf-size)
                         server-recv-ch (achan-buf net end-point-buf-size)
                         close-server-recv-ch (achan net)]
                     ; TODO: Need to model running out of ports.
                     (recur (inc ts) listens
                            (-> streams
                                (assoc client-send-ch {:send-ch client-send-ch
                                                       :recv-ch server-recv-ch
                                                       :close-recv-ch close-server-recv-ch
                                                       :server-addr to-addr :server-port to-port
                                                       :side :client :msgs []})
                                (assoc server-send-ch {:send-ch server-send-ch
                                                       :recv-ch client-recv-ch
                                                       :close-recv-ch close-client-recv-ch
                                                       :server-addr to-addr :server-port to-port
                                                       :side :server :msgs []}))
                            (-> results
                                (conj [result-ch [client-send-ch
                                                  client-recv-ch close-client-recv-ch]])
                                (conj [accept-ch [server-send-ch
                                                  server-recv-ch close-server-recv-ch]]))))
                   (do (aclose net result-ch)
                       (recur (inc ts) listens streams results)))
                 (net-clean-up net listens streams results))
               (some #(= ch %) send-chs)
               (let [send-ch ch
                     stream (get streams send-ch)]
                 (if-let [[msg result-ch] v]
                   (recur (inc ts) listens
                          (if (:recv-ch stream)
                            (update-in streams [send-ch :msgs]
                                       conj [(+ ts (get opts :delivery-delay 0)) msg])
                            streams)
                          (if result-ch
                            (conj results [result-ch :ok])
                            results))
                   (recur (inc ts) listens
                          (if (:recv-ch stream)
                            (assoc-in streams [send-ch :send-closed] true)
                            (dissoc streams send-ch))
                          results)))
               :else ; Test if it's a completed deliverable (recv-ch aput'ed).
               (if-let [stream (first (filter #(= (:recv-ch %) ch) (vals streams)))]
                 (let [stream2 (update-in stream [:msgs] rest)]
                   (if (and (:send-closed stream2)
                            (empty? (:msgs stream2)))
                     (do (aclose net (:recv-ch stream2))
                         (recur (inc ts) listens
                                (dissoc streams (:send-ch stream2))
                                results))
                     (recur (inc ts) listens
                            (assoc streams (:send-ch stream2) stream2)
                            results)))
                 (if-let [stream (first (filter #(= (:close-recv-ch %) ch) (vals streams)))]
                   (if v
                     (recur (inc ts) listens streams results)
                     (do (aclose net (:recv-ch stream)) ; Handle a closed close-recv-ch.
                         (recur (inc ts)
                                listens
                                (assoc streams (:send-ch stream)
                                       (-> stream
                                           (dissoc :msgs)
                                           (dissoc :recv-ch)
                                           (dissoc :close-recv-ch)))
                                results)))
                   (if-let [[addr-port [close-accept-ch accept-ch]]
                            (first (filter #(= ch (first (second %))) (seq listens)))]
                     (if v
                       (recur (inc ts) listens streams results)
                       (do (aclose net accept-ch) ; Handle a closed close-accept-ch.
                           (recur (inc ts)
                                  (dissoc listens addr-port)
                                  streams
                                  results)))
                     (if (seq (filter #(= ch (first %)) results))
                       (do (aclose net ch) ; Handle a completed result-ch aput.
                           (recur (inc ts) listens streams
                                  (remove #(= (first %) ch) results)))
                       (println "UNKNOWN ch"
                                listens :streams streams :results results)))))))))
