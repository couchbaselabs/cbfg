(ns cbfg.net-test
  (:require-macros [cbfg.ago :refer [achan aclose ago ago-loop aput atake]])
  (:require [cbfg.net :refer [make-net]]))

(defn e [n result expect result-nil]
  (let [pass (= result expect)
        my-n (swap! n inc)]
    (println my-n)
    (when (not pass)
      (println (str my-n ":") "FAIL:" result expect))
    (and pass (nil? result-nil))))

(defn test-net [actx]
  (ago tn actx
       (let [n (atom 0)]
         (if (and (let [listen-ch (achan tn)
                        connect-ch (achan tn)
                        net (make-net tn listen-ch connect-ch)]
                    ; Closing listen-ch should shutdown net.
                    (aclose tn listen-ch)
                    (e n (atake tn net) :done (atake tn net)))
                  (let [listen-ch (achan tn)
                        connect-ch (achan tn)
                        net (make-net tn listen-ch connect-ch)]
                    ; Closing connect-ch should shutdown net.
                    (aclose tn connect-ch)
                    (e n (atake tn net) :done (atake tn net)))
                  (let [listen-ch (achan tn)
                        connect-ch (achan tn)
                        net (make-net tn listen-ch connect-ch)
                        listen-result-ch (achan tn)]
                    ; Closing listen-ch should shutdown net and accept-ch's.
                    (aput tn listen-ch [:addr-a 1000 listen-result-ch])
                    (let [[close-accept-ch accept-ch] (atake tn listen-result-ch)]
                      (aclose tn listen-ch)
                      (and (e n (atake tn accept-ch) nil nil)
                           (e n (atake tn net) :done (atake tn net)))))
                  (let [listen-ch (achan tn)
                        connect-ch (achan tn)
                        net (make-net tn listen-ch connect-ch)
                        listen-result-ch (achan tn)]
                    ; Closing connect-ch should shutdown net and accept-ch's.
                    (aput tn listen-ch [:addr-a 1000 listen-result-ch])
                    (let [[close-accept-ch accept-ch] (atake tn listen-result-ch)]
                      (aclose tn connect-ch)
                      (and (e n (atake tn accept-ch) nil nil)
                           (e n (atake tn net) :done (atake tn net)))))
                  (let [listen-ch (achan tn)
                        connect-ch (achan tn)
                        net (make-net tn listen-ch connect-ch)
                        listen-result-ch (achan tn)
                        listen-result-ch2 (achan tn)]
                    ; 2nd listen on same addr/port should fail.
                    (aput tn listen-ch [:addr-a 1000 listen-result-ch])
                    (let [[close-accept-ch accept-ch] (atake tn listen-result-ch)]
                      (aput tn listen-ch [:addr-a 1000 listen-result-ch2])
                      (and (e n (atake tn listen-result-ch2) nil nil)
                           (do (aclose tn listen-ch)
                               (aclose tn connect-ch)
                               (and (e n (atake tn accept-ch) nil nil)
                                    (e n (atake tn net) :done (atake tn net)))))))
                  (let [listen-ch (achan tn)
                        connect-ch (achan tn)
                        net (make-net tn listen-ch connect-ch)
                        connect-result-ch (achan tn)]
                    ; Connecting to unlistened to port should fail.
                    (aput tn connect-ch [:addr-a 1000 :addr-x connect-result-ch])
                    (and (e n (atake tn connect-result-ch) nil nil)
                         (do (aclose tn listen-ch)
                             (aclose tn connect-ch)
                             (e n (atake tn net) :done (atake tn net))))))
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

