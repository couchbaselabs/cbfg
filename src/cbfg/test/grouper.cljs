(ns cbfg.test.grouper
  (:require-macros [cbfg.act :refer [achan aclose act aalts aput atake]])
  (:require [cbfg.grouper :refer [make-grouper]]))

(defn e [n result expect result-nil]
  (let [pass (= result expect)
        my-n (swap! n inc)]
    (when (not pass)
      (println (str my-n ":") "FAIL:" result expect))
    (and pass (nil? result-nil))))

(defn test-grouper [actx]
  (act tg actx
       (let [n (atom 0)
             put-ch (achan tg)
             take-all-ch (achan tg)
             g (make-grouper tg put-ch take-all-ch 2)]
         (if (and (let [r (achan tg)]
                    (aput tg put-ch [:a #(do (act cb tg
                                                  (aput cb r [%])
                                                       (aclose tg r))
                                             :A)])
                    (e n
                       (atake tg r)
                       [nil]
                       (atake tg r)))
                  (let [r (achan tg)]
                    (aput tg put-ch [:a #(do (act cb tg
                                                  (aput cb r [%])
                                                  (aclose tg r))
                                             :AA)])
                    (e n
                       (atake tg r)
                       [:A]
                       (atake tg r)))
                  (e n
                     (atake tg take-all-ch)
                     {:a :AA}
                     nil)
                  (let [r (act take-test tg
                               (atake take-test take-all-ch))]
                    (aput tg put-ch [:b (fn [_] :B)])
                    (e n
                       (atake tg r)
                       {:b :B}
                       (atake tg r)))
                  (do
                    (aput tg put-ch [:a (fn [_] :A)])
                    (aput tg put-ch [:b (fn [_] :B)])
                    (e n
                       (atake tg take-all-ch)
                       {:a :A :b :B}
                       nil))
                  (let [r (act full-test0 tg
                               (aput full-test0 put-ch [:a (fn [_] :A)])
                               (aput full-test0 put-ch [:b (fn [_] :B)])
                               :full-test0)]
                    (e n
                       (atake tg r)
                       :full-test0
                       (atake tg r)))
                  (let [puts-started-ch (achan tg)
                        r (act full-test1 tg
                               (aclose full-test1 puts-started-ch)
                               (aput full-test1 put-ch [:c (fn [_] :C)])
                               (aput full-test1 put-ch [:d (fn [_] :D)])
                               :full-test1)]
                    (and (e n
                            (atake tg puts-started-ch)
                            nil
                            nil)
                         (e n
                            (atake tg take-all-ch)
                            {:a :A :b :B}
                            nil)
                         (e n
                            (atake tg r)
                            :full-test1
                            (atake tg r))
                         (e n
                            (atake tg take-all-ch)
                            {:c :C :d :D}
                            nil)))
                  (do (aclose tg put-ch)
                      (e n
                         (atake tg take-all-ch)
                         nil
                         nil)))
           "pass"
           (str "FAIL: on test-grouper #" @n)))))

(defn test [actx opaque]
  (act test actx
       {:opaque opaque
        :result {"test-grouper"
                 (let [ch (test-grouper test)
                       cv (atake test ch)
                       _  (atake test ch)]
                   cv)}}))

