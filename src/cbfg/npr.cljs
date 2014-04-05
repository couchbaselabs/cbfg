(ns cbfg.npr ; NPR stands for aNother Protocol for Replication, pronounced like UPR.
  (:require-macros [cbfg.ago :refer [achan-buf ago ago-loop aclose
                                     aput aput-close atake]]))

(defprotocol NPRMessage
  "Methods to help form NPR protocol messages"
  (npr-snapshot-beg-msg [stream-request snapshot])
  (npr-snapshot-end-msg [stream-request snapshot])
  (npr-snapshot-item-msg [stream-request snapshot item])
  (npr-rollback-msg [stream-request rollback]))

(defprotocol NPRSever
  "Methods than an NPR protocol server must implement"
  (server-take-snapshot [actx server stream-request prev-snapshot]))

(defprotocol NPRClient
  "Methods than an NPR protocol client must implement"
  (client-stream-request-msg [actx client token])
  (client-rollback [client actx stream-request rollback-msg])
  (client-snapshot-beg [client actx stream-request snapshot-beg])
  (client-snapshot-end [client actx stream-request snapshot-beg snapshot-end])
  (client-snapshot-item [client actx stream-request snapshot-beg snapshot-item]))

(defn make-npr-server-session [actx server stream-request to-client-ch]
  (ago-loop npr-server-session actx
            [stream-request stream-request
             snapshot (server-take-snapshot actx server stream-request nil)
             num-snapshots 0]
            (cond
             (nil? snapshot) (aput-close npr-server-session to-client-ch
                                         (assoc stream-request :status :ok))
             (:rollback snapshot) (aput-close npr-server-session to-client-ch
                                              (npr-rollback-msg stream-request snapshot))
             :else (do (aput npr-server-session to-client-ch
                             (npr-snapshot-beg-msg stream-request snapshot))
                       (doseq [item (:items snapshot)]
                         (aput npr-server-session to-client-ch
                               (npr-snapshot-item-msg stream-request snapshot item)))
                       (aput npr-server-session to-client-ch
                             (npr-snapshot-end-msg stream-request snapshot))
                       (recur stream-request
                              (server-take-snapshot actx server stream-request snapshot)
                              (inc num-snapshots))))))

(defn npr-client-loop [actx client stream-request snapshot-beg out from-server-ch]
  (ago-loop npr-client-loop actx
            [stream-request stream-request
             snapshot-beg nil
             r snapshot-beg
             num-snapshots 0
             num-items 0]
            (cond
             (nil? r) (aput-close npr-client-loop out {:status :closed})
             (= (:status r) :ok) (aput-close npr-client-loop out {:status :ok})
             (and snapshot-beg
                  (= (:status r) :part)
                  (= (:sub-status r) :snapshot-item)) (if-let [err (client-snapshot-item client
                                                                                         npr-client-loop
                                                                                         stream-request
                                                                                         snapshot-beg r)]
                                                        (aput-close npr-client-loop out err)
                                                        (recur stream-request snapshot-beg
                                                               (atake npr-client-loop from-server-ch)
                                                               num-snapshots (inc num-items)))
             (and snapshot-beg
                  (= (:status r) :part)
                  (= (:sub-status r) :snapshot-end)) (if-let [err (client-snapshot-end client
                                                                                       npr-client-loop
                                                                                       stream-request
                                                                                       snapshot-beg r)]
                                                       (aput-close npr-client-loop out err)
                                                       (recur stream-request nil
                                                              (atake npr-client-loop from-server-ch)
                                                              (inc num-snapshots) num-items))
             (and (nil? snapshot-beg)
                  (= (:status r) :part)
                  (= (:sub-status r) :snapshot-beg)) (if-let [err (client-snapshot-beg client
                                                                                       npr-client-loop
                                                                                       stream-request r)]
                                                       (aput-close npr-client-loop out err)
                                                       (recur stream-request r
                                                              (atake npr-client-loop from-server-ch)
                                                              num-snapshots num-items))
             (and (nil? snapshot-beg)
                  (= (:status :rollback))) (aput-close npr-client-loop out
                                                       (client-rollback client
                                                                        npr-client-loop
                                                                        stream-request r))
             :else (aput-close npr-client-loop out {:status :unexpected-msg}))))

(defn make-npr-client-session [actx client token to-server-ch from-server-ch]
  (let [out (achan-buf actx 1)
        stream-request (client-stream-request-msg client actx token)]
    (ago npr-client-session actx
         (aput npr-client-session to-server-ch stream-request)
         (npr-client-loop npr-client-session client stream-request
                          (atake npr-client-session from-server-ch)
                          out from-server-ch))
    out))
