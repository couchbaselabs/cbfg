(ns cbfg.net
  (:require-macros [cbfg.act :refer [achan achan-buf act act-loop aclose aalts]]))

(defn net-clean-up [actx listens streams results]
  (act cleanup actx
       (doseq [[[addr port] [accept-ch accept-close-ch]] listens]
         (aclose cleanup accept-ch))
       (doseq [stream (vals streams)]
         (when (:recv-ch stream)
           (aclose cleanup (:recv-ch stream))))
       (doseq [[keep-open-bool result-ch msg] results]
         (aclose cleanup result-ch)))
  :done)

(defn make-net [actx request-listen-ch request-connect-ch & opts]
  (act-loop net actx     ; A conn is represented by dual streams.
            [ts 0        ; A increasing event / time stamp counter.
             listens {}  ; Keyed by [addr port], value is [accept-ch close-accept-ch].
             streams {}  ; Keyed by send-ch and a value is...
                         ; {:send-addr :send-port :send-ch
                         ;  :recv-addr :recv-port :recv-ch
                         ;  :side (:client|:server) :server-addr :server-port
                         ;  :msgs [[deliver-at msg] ...]}.
             results []] ; A result entry is [keep-open-bool result-ch msg].
            (let [close-accept-chs (map second (vals listens))
                  close-recv-chs (mapcat (fn [[send-ch stream]]
                                           (when-let [close-recv-ch
                                                      (:close-recv-ch stream)]
                                             [close-recv-ch]))
                                         streams)
                  deliverable-msgs (mapcat (fn [[send-ch stream]]
                                             (when-let [[deliver-at msg]
                                                        (first (:msgs stream))]
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
                          (into deliverable-msgs)
                          (into send-chs)
                          (into (map #(vec (rest %)) results)))
                  [v ch] (aalts net chs)]
              (cond
               (= ch request-listen-ch) ; Server listen request.
               (if-let [[addr port result-ch] v]
                 (if (get listens [addr port])
                   (do (aclose net result-ch)
                       (recur (inc ts) listens streams results))
                   (let [accept-chs [(achan net) (achan net)]]
                     (recur (inc ts)
                            (assoc listens [addr port] accept-chs)
                            streams
                            (conj results [false result-ch accept-chs]))))
                 (net-clean-up net listens streams results))
               (= ch request-connect-ch) ; Client connect request.
               (if-let [[accept-addr accept-port from-addr result-ch] v]
                 ; TODO: Need to model connect delay, perhaps with a generic "later".
                 (if-let [[accept-ch close-accept-ch]
                          (get listens [accept-addr accept-port])]
                   (let [from-port (+ 10000 ts)
                         to-port (+ 10000 ts)
                         end-point-buf-size (get opts :end-point-buf-size 0)
                         client-send-ch (achan-buf net end-point-buf-size)
                         client-recv-ch (achan-buf net end-point-buf-size)
                         close-client-recv-ch (achan net)
                         server-send-ch (achan-buf net end-point-buf-size)
                         server-recv-ch (achan-buf net end-point-buf-size)
                         close-server-recv-ch (achan net)]
                     ; TODO: Need to model running out of ports.
                     (recur (inc ts) listens
                            (-> streams
                                (assoc client-send-ch
                                  {:send-ch client-send-ch
                                   :recv-ch server-recv-ch
                                   :close-recv-ch close-server-recv-ch
                                   :accept-addr accept-addr :accept-port accept-port
                                   :server-addr accept-addr :server-port to-port
                                   :client-addr from-addr :client-port from-port
                                   :side :client :msgs []})
                                (assoc server-send-ch
                                  {:send-ch server-send-ch
                                   :recv-ch client-recv-ch
                                   :close-recv-ch close-client-recv-ch
                                   :accept-addr accept-addr :accept-port accept-port
                                   :server-addr accept-addr :server-port to-port
                                   :client-addr from-addr :client-port from-port
                                   :side :server :msgs []}))
                            (-> results
                                (conj [false result-ch
                                       [client-send-ch client-recv-ch
                                        close-client-recv-ch accept-addr to-port]])
                                (conj [true accept-ch
                                       [server-send-ch server-recv-ch
                                        close-server-recv-ch from-addr from-port]]))))
                   (do (aclose net result-ch)
                       (recur (inc ts) listens streams results)))
                 (net-clean-up net listens streams results))
               (some #(= ch %) send-chs) ; A peer put to a send-ch or closed a send-ch.
               (let [send-ch ch
                     stream (get streams send-ch)]
                 (if-let [[msg result-ch] v]
                   (recur (inc ts) listens
                          (if (:recv-ch stream)
                            (update-in streams [send-ch :msgs]
                                       conj [(+ ts (get opts :delivery-delay 0)) msg])
                            streams)
                          (if result-ch
                            (conj results [true result-ch :ok])
                            results))
                   (recur (inc ts) listens
                          (if (:recv-ch stream)
                            (assoc-in streams [send-ch :send-closed] true)
                            (dissoc streams send-ch))
                          results)))
               :else ; Test if it's a completed deliverable (recv-ch aput'ed).
               (if-let [stream (first (filter #(= (:recv-ch %) ch) (vals streams)))]
                 (let [stream2 (update-in stream [:msgs] #(vec (rest %)))]
                   (if (and (:send-closed stream2)
                            (empty? (:msgs stream2)))
                     (do (aclose net (:recv-ch stream2))
                         (recur (inc ts) listens
                                (dissoc streams (:send-ch stream2))
                                results))
                     (recur (inc ts) listens
                            (assoc streams (:send-ch stream2) stream2)
                            results)))
                 (if-let [stream (first (filter #(= (:close-recv-ch %) ch)
                                                (vals streams)))]
                   (if v ; Or, test if it's a closed close-recv-ch.
                     (recur (inc ts) listens streams results)
                     (do (aclose net (:recv-ch stream))
                         (recur (inc ts)
                                listens
                                (assoc streams (:send-ch stream)
                                       (-> stream
                                           (dissoc :msgs)
                                           (dissoc :recv-ch)
                                           (dissoc :close-recv-ch)))
                                results)))
                   (if-let [[addr-port [accept-ch close-accept-ch]]
                            (first (filter #(= ch (second (second %))) (seq listens)))]
                     (if v ; Or, test if it's a closed close-accept-ch.
                       (recur (inc ts) listens streams results)
                       (do (aclose net accept-ch)
                           (recur (inc ts)
                                  (dissoc listens addr-port)
                                  streams
                                  results)))
                     ; Else, was succesful result-ch aput (delivery of result-msg).
                     (if-let [[keep-open-bool result-ch result-msg]
                              (first (filter #(= (second %) ch) results))]
                       (do (when (not keep-open-bool)
                             (aclose net ch))
                           (recur (inc ts) listens streams
                                  (remove #(= (second %) ch) results)))
                       :error-unknown-ch))))))))
