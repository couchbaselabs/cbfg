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
         (if (and true)
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

