(ns cbfg.store-test
  (:require-macros [cbfg.ago :refer [achan aclose ago atake]])
  (:require [cbfg.store :as store]))

(defn e [n result expect result-nil]
  (let [result2 (if (:cas expect)
                  result
                  (dissoc result :cas))
        pass (= result2 expect)]
    (println (str (swap! n inc) ":")
             (if pass
               (str "pass: " (:key expect))
               (str "FAIL:" result2 expect)))
    (and pass (nil? result-nil))))

(defn test-basic [actx]
  (ago test-basic actx
       (let [n (atom 0)
             s (store/make-store test-basic)]
         (if (and (let [c (store/store-get test-basic s @n "does-not-exist")]
                    (e n (atake test-basic c)
                       {:status :not-found, :key "does-not-exist", :opaque @n}
                       (atake test-basic c)))
                  (let [c (store/store-get test-basic s @n "does-not-exist2")]
                    (e n (atake test-basic c)
                       {:status :not-found, :key "does-not-exist2", :opaque @n}
                       (atake test-basic c)))
                  (let [c (store/store-del test-basic s @n "does-not-exist" 0)]
                    (e n (atake test-basic c)
                       {:status :not-found, :key "does-not-exist", :opaque @n}
                       (atake test-basic c)))
                  (let [c (store/store-set test-basic s @n "does-not-exist" 0 ""
                                           :replace)]
                    (e n (atake test-basic c)
                       {:status :not-found, :key "does-not-exist", :opaque @n}
                       (atake test-basic c)))
                  (let [c (store/store-set test-basic s @n "does-not-exist" 0 ""
                                           :append)]
                    (e n (atake test-basic c)
                       {:status :not-found, :key "does-not-exist", :opaque @n}
                       (atake test-basic c)))
                  (let [c (store/store-set test-basic s @n "does-not-exist" 0 ""
                                           :replace)]
                    (e n (atake test-basic c)
                       {:status :not-found, :key "does-not-exist", :opaque @n}
                       (atake test-basic c)))
                  (let [c (store/store-set test-basic s @n "a" 0 "A" :set)]
                    (e n (atake test-basic c)
                       {:status :ok, :key "a", :opaque @n, :sq 1}
                       (atake test-basic c)))
                  (let [c (store/store-get test-basic s @n "a")]
                    (e n (atake test-basic c)
                       {:status :ok, :key "a", :opaque @n, :sq 1, :val "A"}
                       (atake test-basic c)))
                  (let [c (store/store-set test-basic s @n "a" 0 "AA" :set)]
                    (e n (atake test-basic c)
                       {:status :ok, :key "a", :opaque @n, :sq 2}
                       (atake test-basic c)))
                  (let [c (store/store-get test-basic s @n "a")]
                    (e n (atake test-basic c)
                       {:status :ok, :key "a", :opaque @n, :sq 2, :val "AA"}
                       (atake test-basic c)))
                  (let [c (store/store-set test-basic s @n "a" 0 "" :add)]
                    (e n (atake test-basic c)
                       {:status :exists, :key "a", :opaque @n}
                       (atake test-basic c)))
                  (let [c (store/store-get test-basic s @n "a")]
                    (e n (atake test-basic c)
                       {:status :ok, :key "a", :opaque @n, :sq 2, :val "AA"}
                       (atake test-basic c)))
                  (let [c (store/store-set test-basic s @n "a" 0 "AAA" :replace)]
                    (e n (atake test-basic c)
                       {:status :ok, :key "a", :opaque @n, :sq 3}
                       (atake test-basic c)))
                  (let [c (store/store-get test-basic s @n "a")]
                    (e n (atake test-basic c)
                       {:status :ok, :key "a", :opaque @n, :sq 3, :val "AAA"}
                       (atake test-basic c)))
                  (let [c (store/store-set test-basic s @n "a" 0 "suffix" :append)]
                    (e n (atake test-basic c)
                       {:status :ok, :key "a", :opaque @n, :sq 4}
                       (atake test-basic c)))
                  (let [c (store/store-get test-basic s @n "a")]
                    (e n (atake test-basic c)
                       {:status :ok, :key "a", :opaque @n, :sq 4, :val "AAAsuffix"}
                       (atake test-basic c)))
                  (let [c (store/store-set test-basic s @n "a" 0 "prefix" :prepend)]
                    (e n (atake test-basic c)
                       {:status :ok, :key "a", :opaque @n, :sq 5}
                       (atake test-basic c)))
                  (let [c (store/store-get test-basic s @n "a")]
                    (e n (atake test-basic c)
                       {:status :ok, :key "a", :opaque @n, :sq 5, :val "prefixAAAsuffix"}
                       (atake test-basic c)))
                  (let [c (store/store-set test-basic s @n "a" 666 "" :set)]
                    (e n (atake test-basic c)
                       {:status :wrong-cas, :key "a", :opaque @n}
                       (atake test-basic c)))
                  (let [c (store/store-set test-basic s @n "a" 666 "" :add)]
                    (e n (atake test-basic c)
                       {:status :wrong-cas, :key "a", :opaque @n}
                       (atake test-basic c)))
                  (let [c (store/store-set test-basic s @n "a" 666 "" :replace)]
                    (e n (atake test-basic c)
                       {:status :wrong-cas, :key "a", :opaque @n}
                       (atake test-basic c)))
                  (let [c (store/store-set test-basic s @n "a" 666 "" :append)]
                    (e n (atake test-basic c)
                       {:status :wrong-cas, :key "a", :opaque @n}
                       (atake test-basic c)))
                  (let [c (store/store-set test-basic s @n "a" 666 "" :prepend)]
                    (e n (atake test-basic c)
                       {:status :wrong-cas, :key "a", :opaque @n}
                       (atake test-basic c)))
                  (let [c (store/store-del test-basic s @n "a" 666)]
                    (e n (atake test-basic c)
                       {:status :wrong-cas, :key "a", :opaque @n}
                       (atake test-basic c)))
                  (let [gc (store/store-get test-basic s @n "a")
                        gv (atake test-basic gc)
                        _ (atake test-basic gc)
                        sc (store/store-set test-basic s @n "a" (:cas gv) "AAAA" :set)
                        sv (atake test-basic sc)]
                    (and (e n sv
                            {:status :ok, :key "a", :opaque @n, :sq 6, :cas (:cas sv)}
                            (atake test-basic sc))
                         (let [gc2 (store/store-get test-basic s @n "a")]
                           (e n (atake test-basic gc2)
                              {:status :ok, :key "a", :opaque @n, :sq 6, :cas (:cas sv)
                               :val "AAAA"}
                              (atake test-basic gc2)))))
                  (let [gc (store/store-get test-basic s @n "a")
                        gv (atake test-basic gc)
                        _ (atake test-basic gc)
                        sc (store/store-set test-basic s @n "a" (:cas gv) "AAAAA"
                                            :replace)
                        sv (atake test-basic sc)]
                    (and (e n sv
                            {:status :ok, :key "a", :opaque @n, :sq 7, :cas (:cas sv)}
                            (atake test-basic sc))
                         (let [gc2 (store/store-get test-basic s @n "a")]
                           (e n (atake test-basic gc2)
                              {:status :ok, :key "a", :opaque @n, :sq 7, :cas (:cas sv)
                               :val "AAAAA"}
                              (atake test-basic gc2)))))
                  (let [gc (store/store-get test-basic s @n "a")
                        gv (atake test-basic gc)
                        _ (atake test-basic gc)
                        sc (store/store-set test-basic s @n "a" (:cas gv) "suffix"
                                            :append)
                        sv (atake test-basic sc)]
                    (and (e n sv
                            {:status :ok, :key "a", :opaque @n, :sq 8, :cas (:cas sv)}
                            (atake test-basic sc))
                         (let [gc2 (store/store-get test-basic s @n "a")]
                           (e n (atake test-basic gc2)
                              {:status :ok, :key "a", :opaque @n, :sq 8, :cas (:cas sv)
                               :val "AAAAAsuffix"}
                              (atake test-basic gc2)))))
                  (let [gc (store/store-get test-basic s @n "a")
                        gv (atake test-basic gc)
                        _ (atake test-basic gc)
                        sc (store/store-set test-basic s @n "a" (:cas gv) "prefix"
                                            :prepend)
                        sv (atake test-basic sc)]
                    (and (e n sv
                            {:status :ok, :key "a", :opaque @n, :sq 9, :cas (:cas sv)}
                            (atake test-basic sc))
                         (let [gc2 (store/store-get test-basic s @n "a")]
                           (e n (atake test-basic gc2)
                              {:status :ok, :key "a", :opaque @n, :sq 9, :cas (:cas sv)
                               :val "prefixAAAAAsuffix"}
                              (atake test-basic gc2)))))
                  (let [gc (store/store-get test-basic s @n "a")
                        gv (atake test-basic gc)
                        _ (atake test-basic gc)
                        dc (store/store-del test-basic s @n "a" (:cas gv))
                        dv (atake test-basic dc)]
                    (and (e n dv
                            {:status :ok, :key "a", :opaque @n, :sq 10}
                            (atake test-basic dc))
                         (let [gc2 (store/store-get test-basic s @n "a")]
                           (e n (atake test-basic gc2)
                              {:status :not-found, :key "a", :opaque @n}
                              (atake test-basic gc2))))))
           "pass"
           (str "FAIL: on test-basic #" @n)))))

(defn test-scan [actx]
  (ago test-scan actx
       (let [n (atom 0)
             s (store/make-store test-scan)]
         (if (and (let [c (store/store-scan test-scan s @n "a" "z")]
                    (e n (atake test-scan c)
                       {:status :ok, :opaque @n}
                       (atake test-scan c)))
                  (let [c (store/store-set test-scan s @n "a" 0 "A" :set)]
                    (e n (atake test-scan c)
                       {:status :ok, :key "a", :opaque @n, :sq 1}
                       (atake test-scan c)))
                  (let [c (store/store-scan test-scan s @n "b" "z")]
                    (e n (atake test-scan c)
                       {:status :ok, :opaque @n}
                       (atake test-scan c)))
                  (let [c (store/store-scan test-scan s @n "a" "a")]
                    (e n (atake test-scan c)
                       {:status :ok, :opaque @n}
                       (atake test-scan c)))
                  (let [c (store/store-scan test-scan s @n "a" "z")
                        m @n]
                    (and (e n (atake test-scan c)
                            {:status :part, :opaque m, :key "a", :sq 1, :val "A"}
                            nil)
                         (e n (atake test-scan c)
                            {:status :ok, :opaque m}
                            (atake test-scan c))))
                  (let [c (store/store-set test-scan s @n "c" 0 "C" :set)]
                    (e n (atake test-scan c)
                       {:status :ok, :key "c", :opaque @n, :sq 2}
                       (atake test-scan c)))
                  (let [c (store/store-scan test-scan s @n "a" "z")
                        m @n]
                    (and (e n (atake test-scan c)
                            {:status :part, :opaque m, :key "a", :sq 1, :val "A"}
                            nil)
                         (e n (atake test-scan c)
                            {:status :part, :opaque m, :key "c", :sq 2, :val "C"}
                            nil)
                         (e n (atake test-scan c)
                            {:status :ok, :opaque m}
                            (atake test-scan c))))
                  (let [c (store/store-scan test-scan s @n "a" "c")
                        m @n]
                    (and (e n (atake test-scan c)
                            {:status :part, :opaque m, :key "a", :sq 1, :val "A"}
                            nil)
                         (e n (atake test-scan c)
                            {:status :ok, :opaque m}
                            (atake test-scan c))))
                  (let [c (store/store-scan test-scan s @n "b" "c")
                        m @n]
                    (and (e n (atake test-scan c)
                            {:status :ok, :opaque m}
                            (atake test-scan c))))
                  (let [c (store/store-scan test-scan s @n "b" "z")
                        m @n]
                    (and (e n (atake test-scan c)
                            {:status :part, :opaque m, :key "c", :sq 2, :val "C"}
                            nil)
                         (e n (atake test-scan c)
                            {:status :ok, :opaque m}
                            (atake test-scan c))))
                  (let [c (store/store-del test-scan s @n "c" 0)]
                    (e n (atake test-scan c)
                       {:status :ok, :key "c", :opaque @n, :sq 3}
                       (atake test-scan c)))
                  (let [c (store/store-scan test-scan s @n "b" "z")]
                    (e n (atake test-scan c)
                       {:status :ok, :opaque @n}
                       (atake test-scan c)))
                  (let [c (store/store-scan test-scan s @n "a" "a")]
                    (e n (atake test-scan c)
                       {:status :ok, :opaque @n}
                       (atake test-scan c)))
                  (let [c (store/store-scan test-scan s @n "a" "z")
                        m @n]
                    (and (e n (atake test-scan c)
                            {:status :part, :opaque m, :key "a", :sq 1, :val "A"}
                            nil)
                         (e n (atake test-scan c)
                            {:status :ok, :opaque m}
                            (atake test-scan c))))
                  (let [c (store/store-del test-scan s @n "a" 0)]
                    (e n (atake test-scan c)
                       {:status :ok, :key "a", :opaque @n, :sq 4}
                       (atake test-scan c)))
                  (let [c (store/store-scan test-scan s @n "a" "z")]
                    (e n (atake test-scan c)
                       {:status :ok, :opaque @n}
                       (atake test-scan c))))
           "pass"
           (str "FAIL: on test-scan #" @n)))))

(defn test-changes [actx]
  (ago test-changes actx
       (let [n (atom 0)
             s (store/make-store test-changes)]
         (if (and (let [c (store/store-changes test-changes s @n 0 100)]
                    (e n (atake test-changes c)
                       {:status :ok, :opaque @n}
                       (atake test-changes c))))
           "pass"
           (str "FAIL: on test-changes #" @n)))))

(defn test [actx opaque]
  (ago test actx
       {:opaque opaque
        :result {"test-basic"
                 (let [ch (test-basic test)
                       cv (atake test ch)
                       _  (atake test ch)]
                   cv)
                 "test-scan"
                 (let [ch (test-scan test)
                       cv (atake test ch)
                       _  (atake test ch)]
                   cv)
                 "test-changes"
                 (let [ch (test-changes test)
                       cv (atake test ch)
                       _  (atake test ch)]
                   cv)}}))
