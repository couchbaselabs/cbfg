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
         (if (and (let [listen-ch (achan tn)
                        connect-ch (achan tn)
                        net (make-net tn listen-ch connect-ch)]
                    (aclose tn listen-ch) ; Closing listen-ch should shutdown net.
                    (e n (atake tn net) :done (atake tn net)))
                  (let [listen-ch (achan tn)
                        connect-ch (achan tn)
                        net (make-net tn listen-ch connect-ch)]
                    (aclose tn connect-ch) ; Closing connect-ch should shutdown net.
                    (e n (atake tn net) :done (atake tn net)))
                  (let [listen-ch (achan tn)
                        connect-ch (achan tn)
                        net (make-net tn listen-ch connect-ch)
                        accept-ch (achan tn)]
                    (aput tn listen-ch [:host-a 1000 accept-ch])
                    (aclose tn listen-ch) ; Closing listen-ch should shutdown net and accept-ch's.
                    (and (e n (atake tn accept-ch) nil nil)
                         (e n (atake tn net) :done (atake tn net))))
                  (let [listen-ch (achan tn)
                        connect-ch (achan tn)
                        net (make-net tn listen-ch connect-ch)
                        accept-ch (achan tn)]
                    (aput tn listen-ch [:host-a 1000 accept-ch])
                    (aclose tn connect-ch) ; Closing connect-ch should shutdown net and accept-ch's.
                    (and (e n (atake tn accept-ch) nil nil)
                         (e n (atake tn net) :done (atake tn net)))))
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

