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
                                  (atake tkvs res-ch2))))))))
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
