(ns cbfg.net-test
  (:require-macros [cbfg.ago :refer [achan aclose ago ago-loop aput atake]])
  (:require [cbfg.net :refer [make-net]]))

(defn e [n result expect result-nil]
  (let [pass (= result expect)
        my-n (swap! n inc)]
    (when (not pass)
      (println (str my-n ":") "FAIL:" result expect))
    (and pass (nil? result-nil))))

(defn test-net [actx]
  (ago tn actx
       (let [n (atom 0)]
         (if (and
              ; Closing listen-ch should shutdown net.
              (let [listen-ch (achan tn)
                    connect-ch (achan tn)
                    net (make-net tn listen-ch connect-ch)]
                (aclose tn listen-ch)
                (e n (atake tn net) :done (atake tn net)))
              ; Closing connect-ch should shutdown net.
              (let [listen-ch (achan tn)
                    connect-ch (achan tn)
                    net (make-net tn listen-ch connect-ch)]
                (aclose tn connect-ch)
                (e n (atake tn net) :done (atake tn net)))
              ; Closing listen-ch should shutdown net and accept-ch's.
              (let [listen-ch (achan tn)
                    connect-ch (achan tn)
                    net (make-net tn listen-ch connect-ch)
                    listen-result-ch (achan tn)]
                (aput tn listen-ch [:addr-a 1000 listen-result-ch])
                (let [[close-accept-ch accept-ch] (atake tn listen-result-ch)]
                  (aclose tn listen-ch)
                  (and (e n (atake tn accept-ch) nil nil)
                       (e n (atake tn net) :done (atake tn net)))))
              ; Closing connect-ch should shutdown net and accept-ch's.
              (let [listen-ch (achan tn)
                    connect-ch (achan tn)
                    net (make-net tn listen-ch connect-ch)
                    listen-result-ch (achan tn)]
                (aput tn listen-ch [:addr-a 1000 listen-result-ch])
                (let [[close-accept-ch accept-ch] (atake tn listen-result-ch)]
                  (aclose tn connect-ch)
                  (and (e n (atake tn accept-ch) nil nil)
                       (e n (atake tn net) :done (atake tn net)))))
              ; 2nd listen on same addr/port should fail.
              (let [listen-ch (achan tn)
                    connect-ch (achan tn)
                    net (make-net tn listen-ch connect-ch)
                    listen-result-ch (achan tn)
                    listen-result-ch2 (achan tn)]
                (aput tn listen-ch [:addr-a 1000 listen-result-ch])
                (let [[close-accept-ch accept-ch] (atake tn listen-result-ch)]
                  (aput tn listen-ch [:addr-a 1000 listen-result-ch2])
                  (and (e n (atake tn listen-result-ch2) nil nil)
                       (do (aclose tn listen-ch)
                           (aclose tn connect-ch)
                           (and (e n (atake tn accept-ch) nil nil)
                                (e n (atake tn net) :done (atake tn net)))))))
              ; Connecting to unlistened to port should fail.
              (let [listen-ch (achan tn)
                    connect-ch (achan tn)
                    net (make-net tn listen-ch connect-ch)
                    connect-result-ch (achan tn)]
                (aput tn connect-ch [:addr-a 1000 :addr-x connect-result-ch])
                (and (e n (atake tn connect-result-ch) nil nil)
                     (do (aclose tn listen-ch)
                         (aclose tn connect-ch)
                         (e n (atake tn net) :done (atake tn net)))))
              ; Close of close-accept-ch should close accept-ch and fail new connects.
              (let [listen-ch (achan tn)
                    connect-ch (achan tn)
                    net (make-net tn listen-ch connect-ch)
                    listen-result-ch (achan tn)]
                (aput tn listen-ch [:addr-a 1000 listen-result-ch])
                (let [[close-accept-ch accept-ch] (atake tn listen-result-ch)]
                  (aclose tn close-accept-ch)
                  (and (e n (atake tn accept-ch) nil nil)
                       (let [connect-result-ch (achan tn)]
                         (aput tn connect-ch [:addr-a 1000 :addr-x connect-result-ch])
                         (and (e n (atake tn connect-result-ch) nil nil)
                              (do (aclose tn listen-ch)
                                  (aclose tn connect-ch)
                                  (e n (atake tn net) :done (atake tn net))))))))
              ; Try sending 1 msg between client and server and vice-versa.
              (let [listen-ch (achan tn)
                    connect-ch (achan tn)
                    net (make-net tn listen-ch connect-ch)
                    listen-result-ch (achan tn)]
                (aput tn listen-ch [:addr-a 1000 listen-result-ch])
                (let [[close-accept-ch accept-ch] (atake tn listen-result-ch)]
                  (and (e n (not (nil? close-accept-ch)) true nil)
                       (e n (not (nil? accept-ch)) true nil)
                       (e n (atake tn listen-result-ch) nil nil)
                       (let [connect-result-ch (achan tn)]
                         (aput tn connect-ch [:addr-a 1000 :addr-x connect-result-ch])
                         (let [[client-send-ch client-recv-ch close-client-recv-ch]
                               (atake tn connect-result-ch)]
                           (and (e n (not (nil? client-send-ch)) true nil)
                                (e n (not (nil? client-recv-ch)) true nil)
                                (e n (not (nil? close-client-recv-ch)) true nil)
                                (e n (atake tn connect-result-ch) nil nil)
                                (let [[server-send-ch server-recv-ch close-server-recv-ch]
                                      (atake tn accept-ch)]
                                  (and (e n (not (nil? server-send-ch)) true nil)
                                       (e n (not (nil? server-recv-ch)) true nil)
                                       (e n (not (nil? close-server-recv-ch)) true nil)
                                       (do (aput tn client-send-ch [:hi-from-client])
                                           (and (e n (atake tn server-recv-ch) :hi-from-client nil)
                                                (do (aput tn server-send-ch [:hi-from-server])
                                                    (and (e n (atake tn client-recv-ch)
                                                            :hi-from-server nil)
                                                         (do (aclose tn close-server-recv-ch)
                                                             (aclose tn close-client-recv-ch)
                                                             (aclose tn server-send-ch)
                                                             (aclose tn client-send-ch)
                                                             (e n (atake tn server-recv-ch) nil nil)
                                                             (e n (atake tn client-recv-ch) nil nil)
                                                             (aclose tn listen-ch)
                                                             (aclose tn connect-ch)
                                                             (e n (atake tn net)
                                                                :done
                                                                (atake tn net)))))))))))))))
              ; Try sending multiple msgs between client to server.
              (let [listen-ch (achan tn)
                    connect-ch (achan tn)
                    net (make-net tn listen-ch connect-ch)
                    listen-result-ch (achan tn)]
                (aput tn listen-ch [:addr-a 1001 listen-result-ch])
                (let [[close-accept-ch accept-ch] (atake tn listen-result-ch)]
                  (and (e n (not (nil? close-accept-ch)) true nil)
                       (e n (not (nil? accept-ch)) true nil)
                       (e n (atake tn listen-result-ch) nil nil)
                       (let [connect-result-ch (achan tn)]
                         (aput tn connect-ch [:addr-a 1001 :addr-x connect-result-ch])
                         (let [[client-send-ch client-recv-ch close-client-recv-ch]
                               (atake tn connect-result-ch)]
                           (and (e n (not (nil? client-send-ch)) true nil)
                                (e n (not (nil? client-recv-ch)) true nil)
                                (e n (not (nil? close-client-recv-ch)) true nil)
                                (e n (atake tn connect-result-ch) nil nil)
                                (let [[server-send-ch server-recv-ch close-server-recv-ch]
                                      (atake tn accept-ch)]
                                  (and (e n (not (nil? server-send-ch)) true nil)
                                       (e n (not (nil? server-recv-ch)) true nil)
                                       (e n (not (nil? close-server-recv-ch)) true nil)
                                       (do (aput tn client-send-ch [:hi-from-client0])
                                           (aput tn client-send-ch [:hi-from-client1])
                                           (aput tn client-send-ch [:hi-from-client2])
                                           (aput tn client-send-ch [:hi-from-client3])
                                           (aclose tn client-send-ch)
                                           (and (e n (atake tn server-recv-ch) :hi-from-client0 nil)
                                                (e n (atake tn server-recv-ch) :hi-from-client1 nil)
                                                (e n (atake tn server-recv-ch) :hi-from-client2 nil)
                                                (e n (atake tn server-recv-ch) :hi-from-client3 nil)
                                                (e n (atake tn server-recv-ch) nil nil)
                                                (do (aclose tn close-server-recv-ch)
                                                    (aclose tn close-client-recv-ch)
                                                    (aclose tn server-send-ch)
                                                    (e n (atake tn server-recv-ch) nil nil)
                                                    (e n (atake tn client-recv-ch) nil nil)
                                                    (aclose tn listen-ch)
                                                    (aclose tn connect-ch)
                                                    (e n (atake tn net)
                                                       :done
                                                       (atake tn net))))))))))))))
           "pass"
           (str "FAIL: on test-net #" @n)))))

(defn test [actx opaque]
  (ago test actx
       {:opaque opaque
        :result {"test-net"
                 (let [ch (test-net test)
                       cv (atake test ch)
                       _  (atake test ch)]
                   cv)}}))

