(ns cbfg.npr-test
  (:require-macros [cbfg.ago :refer [achan aclose ago ago-loop aput atake]])
  (:require [cbfg.npr :refer [NPRServer NPRClient NPRStreamRequest NPRSnapshot
                              make-npr-server-session
                              make-npr-client-session]]))

(defn e [n result expect result-nil]
  (let [pass (= result expect)
        my-n (swap! n inc)]
    (when (not pass)
      (println (str my-n ":") "FAIL:" result expect))
    (and pass (nil? result-nil))))

(defrecord TestNPRStreamRequest [stream-request]
  NPRStreamRequest
  (stream-request-snapshot-beg-msg [this snapshot]
    (merge stream-request {:status :part :sub-status :snapshot-beg
                           :snapshot snapshot}))
  (stream-request-snapshot-end-msg [this snapshot]
    (merge stream-request {:status :part :sub-status :snapshot-end
                           :snapshot snapshot}))
  (stream-request-snapshot-item-msg [this snapshot item]
    (merge stream-request {:status :part :sub-status :snapshot-item
                           :snapshot snapshot
                           :item item}))
  (stream-request-rollback-msg [this rollback-info]
    (merge stream-request {:status :rollback
                           :rollback-info rollback-info}))
  (stream-request-start-sq [this]
    (:start-sq stream-request)))

(defrecord TestNPRSnapshot [snapshot]
  NPRSnapshot
  (snapshot-rollback? [this] false)
  (snapshot-items [this] (:items snapshot))
  (snapshot-next-sq [this] (:next-sq snapshot)))

(defrecord TestNPRServer [server]
  NPRServer
  (server-take-snapshot [this actx stream-request prev-snapshot]
    (let [start-sq (max (cbfg.npr/stream-request-start-sq stream-request)
                        (if prev-snapshot
                          (cbfg.npr/snapshot-next-sq prev-snapshot)
                          -1))]
      (when (< start-sq (count (:items @server)))
        (let [items (nthnext (:items @server) start-sq)]
          (when (> (count items) 0)
            (TestNPRSnapshot. {:items items
                               :next-sq (+ start-sq (count items))})))))))

(defrecord TestNPRClient [client]
  NPRClient
  (client-stream-request-msg [this actx token]
    (TestNPRStreamRequest. (merge token {:op :stream-request
                                         :start-sq (count (:items @client))})))
  (client-rollback [this actx stream-request rollback-msg]
    (swap! client #(update-in % [:hist] conj rollback-msg))
    nil)
  (client-snapshot-beg [this actx stream-request snapshot-beg]
    (swap! client #(update-in % [:hist] conj snapshot-beg))
    nil)
  (client-snapshot-end [this actx stream-request snapshot-beg snapshot-end]
    (swap! client #(update-in % [:hist] conj snapshot-end))
    nil)
  (client-snapshot-item [this actx stream-request snapshot-beg snapshot-item]
    (swap! client #(-> %
                       (update-in [:hist] conj snapshot-item)
                       (update-in [:items] conj (:item snapshot-item))))
    nil))

(defn test-npr [actx]
  (ago tn actx
       (let [n (atom 0)]
         (if (and (let [client-to-server-ch (achan tn)
                        server-to-client-ch (achan tn)
                        server-atom (atom {:hist [] :items [2 4 6]})
                        server (TestNPRServer. server-atom)
                        client-atom (atom {:hist [] :items []})
                        client (TestNPRClient. client-atom)
                        cs (make-npr-client-session tn
                                                    client {:opaque 1 :lane "a"}
                                                    client-to-server-ch
                                                    server-to-client-ch)
                        stream-request (atake tn client-to-server-ch)
                        ss (make-npr-server-session tn
                                                    server
                                                    stream-request
                                                    server-to-client-ch)
                        cresult (atake tn cs)
                        sresult (atake tn ss)]
                    (and (e n nil sresult nil)
                         (e n (:status cresult) :ok nil)
                         (e n (:items @client-atom) [2 4 6] nil)
                         (e n (map (fn [x] [(:status x) (:sub-status x)])
                                   (:hist @client-atom))
                            [[:part :snapshot-beg]
                             [:part :snapshot-item]
                             [:part :snapshot-item]
                             [:part :snapshot-item]
                             [:part :snapshot-end]]
                            nil))))
           "pass"
           (str "FAIL: on test-lane #" @n)))))

(defn test [actx opaque]
  (ago test actx
       {:opaque opaque
        :result {"test-npr"
                 (let [ch (test-npr test)
                       cv (atake test ch)
                       _  (atake test ch)]
                   cv)}}))

