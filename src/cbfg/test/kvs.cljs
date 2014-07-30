(ns cbfg.test.kvs
  (:require-macros [cbfg.act :refer [achan aclose act act-loop aput atake]])
  (:require [cbfg.kvs :refer [make-kvs-mgr]]))

(defn e [n result expect result-nil]
  (let [pass (= result expect)
        my-n (swap! n inc)]
    (when (not pass)
      (println (str my-n ":") "FAIL:" result expect))
    (and pass (nil? result-nil))))

(defn test-kvs [actx]
  (act tkvs actx
       (let [n (atom 0)
             cmd-ch (make-kvs-mgr tkvs)]
         (if (and (let [res-ch (achan tkvs)
                        m {:opaque @n
                           :res-ch res-ch
                           :op :kvs-remove}]
                    (aput tkvs cmd-ch m)
                    (e n
                       (atake tkvs res-ch)
                       (merge m {:status :invalid
                                 :status-info :missing-kvs-ident-arg})
                       (atake tkvs res-ch)))
                  (let [res-ch (achan tkvs)
                        m {:opaque @n
                           :res-ch res-ch
                           :op :kvs-remove
                           :kvs-ident [:bogus :ident]}]
                    (aput tkvs cmd-ch m)
                    (e n
                       (atake tkvs res-ch)
                       (merge m {:status :not-found
                                 :status-info [:no-kvs (:kvs-ident m)]})
                       (atake tkvs res-ch)))
                  (let [res-ch (achan tkvs)
                        m {:opaque @n
                           :res-ch res-ch
                           :op :kvs-open
                           :name :foo}]
                    (aput tkvs cmd-ch m)
                    (let [res (atake tkvs res-ch)]
                      (and (e n res
                              (merge m {:status :ok
                                        :status-info :created
                                        :kvs-ident (:kvs-ident res)})
                              (atake tkvs res-ch))
                           (let [res-ch2 (achan tkvs)
                                 m2 {:opaque @n
                                     :res-ch res-ch2
                                     :op :kvs-open
                                     :name :foo}]
                             (aput tkvs cmd-ch m2)
                             (let [res2 (atake tkvs res-ch2)]
                               (e n res2
                                  (merge m2 {:status :ok
                                             :status-info :reopened
                                             :kvs-ident (:kvs-ident res)})
                                  (atake tkvs res-ch2))))
                           (let [res-ch3 (achan tkvs)
                                 m3 {:opaque @n
                                     :res-ch res-ch3
                                     :op :kvs-remove
                                     :kvs-ident (:kvs-ident res)}]
                             (aput tkvs cmd-ch m3)
                             (let [res3 (atake tkvs res-ch3)]
                               (e n res3
                                  (merge m3 {:status :ok
                                             :status-info :removed})
                                  (atake tkvs res-ch3))))
                           (let [res-ch4 (achan tkvs)
                                 m4 {:opaque @n
                                     :res-ch res-ch4
                                     :op :kvs-remove
                                     :kvs-ident (:kvs-ident res)}]
                             (aput tkvs cmd-ch m4)
                             (let [res4 (atake tkvs res-ch4)]
                               (e n res4
                                  (merge m4 {:status :not-found
                                             :status-info [:no-kvs (:kvs-ident res)]})
                                  (atake tkvs res-ch4))))
                           (let [res-ch5 (achan tkvs)
                                 m5 {:opaque @n
                                     :res-ch res-ch5
                                     :op :multi-get
                                     :kvs-ident (:kvs-ident res)}]
                             (aput tkvs cmd-ch m5)
                             (let [res5 (atake tkvs res-ch5)]
                               (e n res5
                                  (merge m5 {:status :not-found
                                             :status-info [:no-kvs (:kvs-ident res)]})
                                  (atake tkvs res-ch5)))))))
                  (let [open-ch (achan tkvs)
                        m {:opaque @n
                           :res-ch open-ch
                           :op :kvs-open
                           :name :kvs0}]
                    (aput tkvs cmd-ch m)
                    (let [open (atake tkvs open-ch)]
                      (and (e n open
                              (merge m {:status :ok
                                        :status-info :created
                                        :kvs-ident (:kvs-ident open)})
                              (atake tkvs open-ch))
                           (let [res-ch2 (achan tkvs)
                                 m2 {:opaque @n
                                     :res-ch res-ch2
                                     :op :multi-get
                                     :kvs-ident (:kvs-ident open)
                                     :keys []}]
                             (aput tkvs cmd-ch m2)
                             (let [res2 (atake tkvs res-ch2)]
                               (e n res2
                                  (dissoc (merge m2 {:status :ok})
                                          :keys)
                                  (atake tkvs res-ch2)))))))

                    )
           "pass"
           (str "FAIL: on test-lane #" @n)))))

(defn test [actx opaque]
  (act test actx
       {:opaque opaque
        :result {"test-kvs"
                 (let [ch (test-kvs test)
                       cv (atake test ch)
                       _  (atake test ch)]
                   cv)}}))

