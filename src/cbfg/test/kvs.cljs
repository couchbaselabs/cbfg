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
                        m {:res-ch res-ch :op :kvs-remove}]
                    (aput tkvs cmd-ch m)
                    (e n
                       (atake tkvs res-ch)
                       (merge m {:status :invalid
                                 :status-info :missing-kvs-ident-arg})
                       (atake tkvs res-ch)))
                  (let [res-ch (achan tkvs)
                        m {:res-ch res-ch :op :kvs-remove :kvs-ident [:bogus :ident]}]
                    (aput tkvs cmd-ch m)
                    (e n
                       (atake tkvs res-ch)
                       (merge m {:status :not-found
                                 :status-info [:no-kvs (:kvs-ident m)]})
                       (atake tkvs res-ch))))
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

