(ns cbfg.grouper-test
  (:require-macros [cbfg.ago :refer [achan aclose ago aalts aput atake]])
  (:require [cbfg.grouper :refer [make-grouper]]))

(defn e [n result expect result-nil]
  (let [pass (= result expect)
        my-n (swap! n inc)]
    (when (not pass)
      (println (str my-n ":") "FAIL:" result expect))
    (and pass (nil? result-nil))))

(defn test-grouper [actx]
  (ago tg actx
       (let [n (atom 0)
             g (make-grouper tg 2)]
         (if (and (let [r (achan tg)]
                    (aput tg (:put-ch g) [:a #(do (ago cb tg
                                                       (aput cb r [%])
                                                       (aclose tg r))
                                                  :A)])
                    (e n
                       (atake tg r)
                       [nil]
                       (atake tg r)))
                  (let [r (achan tg)]
                    (aput tg (:put-ch g) [:a #(do (ago cb tg
                                                       (aput cb r [%])
                                                       (aclose tg r))
                                                  :AA)])
                    (e n
                       (atake tg r)
                       [:A]
                       (atake tg r)))
                  (e n
                     (atake tg (:take-all-ch g))
                     {:a :AA}
                     nil)
                  (let [r (ago take-test tg
                               (atake take-test (:take-all-ch g)))]
                    (aput tg (:put-ch g) [:b (fn [_] :B)])
                    (e n
                       (atake tg r)
                       {:b :B}
                       (atake tg r))))
           "pass"
           (str "FAIL: on test-grouper #" @n)))))

(defn test [actx opaque]
  (ago test actx
       {:opaque opaque
        :result {"test-grouper"
                 (let [ch (test-grouper test)
                       cv (atake test ch)
                       _  (atake test ch)]
                   cv)}}))

