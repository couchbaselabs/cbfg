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
    (println "snapshot-beg-msg" snapshot)
    (merge stream-request {:status :part :sub-status :snapshot-beg
                           :snapshot snapshot}))
  (stream-request-snapshot-end-msg [this snapshot]
    (println "snapshot-end-msg" snapshot)
    (merge stream-request {:status :part :sub-status :snapshot-end
                           :snapshot snapshot}))
  (stream-request-snapshot-item-msg [this snapshot item]
    (println "snapshot-item-msg" snapshot item)
    (merge stream-request {:status :part :sub-status :snapshot-item
                           :snapshot snapshot
                           :item item}))
  (stream-request-rollback-msg [this rollback-info]
    (println "snapshot-rollback-msg" rollback-info)
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
    (println "server-take-snapshot" stream-request prev-snapshot)
    (let [start-sq (max (cbfg.npr/stream-request-start-sq stream-request)
                        (if prev-snapshot
                          (cbfg.npr/snapshot-next-sq prev-snapshot)
                          -1))]
      (println "server-take-snapshot start-sq" start-sq)
      (when (< start-sq (count (:items @server)))
        (let [items (nthnext (:items @server) start-sq)]
          (when (> (count items) 0)
            (println "server-take-snapshot2 start-sq" start-sq "items" items)
            (TestNPRSnapshot. {:items items
                               :next-sq (+ start-sq (count items))})))))))

(defrecord TestNPRClient [catom]
  NPRClient
  (client-stream-request-msg [client actx token]
    (TestNPRStreamRequest. (merge token {:op :stream-request
                                         :start-sq (count (:items @catom))})))
  (client-rollback [client actx stream-request rollback-msg]
    (println "client-rollback" stream-request rollback-msg)
    (swap! client #(update-in % [:hist] conj {:stream-request stream-request
                                              :rollback-msg rollback-msg}))
    nil)
  (client-snapshot-beg [client actx stream-request snapshot-beg]
    (println "client-snapshot-beg" stream-request snapshot-beg)
    (swap! client #(update-in % [:hist] conj {:stream-request stream-request
                                              :snapshot-beg snapshot-beg}))
    nil)
  (client-snapshot-end [client actx stream-request snapshot-beg snapshot-end]
    (println "client-snapshot-end" stream-request snapshot-end)
    (swap! client #(update-in % [:hist] conj {:stream-request stream-request
                                              :snapshot-beg snapshot-beg
                                              :snapshot-end snapshot-end}))
    nil)
  (client-snapshot-item [client actx stream-request snapshot-beg snapshot-item]
    (println "client-snapshot-item" stream-request snapshot-item)
    (swap! client #(update-in % [:hist] conj {:stream-request stream-request
                                              :snapshot-beg snapshot-beg
                                              :snapshot-item snapshot-item}))
    nil))

(defn test-npr [actx]
  (ago tn actx
       (let [n (atom 0)]
         (if (and (let [client-to-server-ch (achan tn)
                        server-to-client-ch (achan tn)
                        server (TestNPRServer. (atom {:hist [] :items [2 4 6]}))
                        client (TestNPRClient. (atom {:hist [] :items []}))
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
                    (do (println cresult)
                        (println sresult)
                        (println server)
                        (println client)
                        true)))
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

