(ns cbfg.store-test
  (:require-macros [cbfg.ago :refer [achan aclose ago atake]])
  (:require [cljs.core.async :refer [into]]
            [cbfg.store :as store]))

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

(defn test [actx opaque-id]
  (ago test actx
       (let [n (atom 0)
             s (store/make-store test)]
         (if (and (let [c (store/store-get test s @n "does-not-exist")]
                    (e n (atake test c)
                       {:status :not-found, :key "does-not-exist", :opaque @n}
                       (atake test c)))
                  (let [c (store/store-get test s @n "does-not-exist2")]
                    (e n (atake test c)
                       {:status :not-found, :key "does-not-exist2", :opaque @n}
                       (atake test c)))
                  (let [c (store/store-del test s @n "does-not-exist" 0)]
                    (e n (atake test c)
                       {:status :not-found, :key "does-not-exist", :opaque @n}
                       (atake test c)))
                  (let [c (store/store-set test s @n "does-not-exist" 0 "" :replace)]
                    (e n (atake test c)
                       {:status :not-found, :key "does-not-exist", :opaque @n}
                       (atake test c)))
                  (let [c (store/store-set test s @n "does-not-exist" 0 "" :append)]
                    (e n (atake test c)
                       {:status :not-found, :key "does-not-exist", :opaque @n}
                       (atake test c)))
                  (let [c (store/store-set test s @n "does-not-exist" 0 "" :replace)]
                    (e n (atake test c)
                       {:status :not-found, :key "does-not-exist", :opaque @n}
                       (atake test c)))
                  (let [c (store/store-set test s @n "a" 0 "A" :set)]
                    (e n (atake test c)
                       {:status :ok, :key "a", :opaque @n, :sq 1}
                       (atake test c)))
                  (let [c (store/store-get test s @n "a")]
                    (e n (atake test c)
                       {:status :ok, :key "a", :opaque @n, :sq 1, :val "A"}
                       (atake test c)))
                  (let [c (store/store-set test s @n "a" 0 "AA" :set)]
                    (e n (atake test c)
                       {:status :ok, :key "a", :opaque @n, :sq 2}
                       (atake test c)))
                  (let [c (store/store-get test s @n "a")]
                    (e n (atake test c)
                       {:status :ok, :key "a", :opaque @n, :sq 2, :val "AA"}
                       (atake test c)))
                  (let [c (store/store-set test s @n "a" 0 "" :add)]
                    (e n (atake test c)
                       {:status :exists, :key "a", :opaque @n}
                       (atake test c)))
                  (let [c (store/store-get test s @n "a")]
                    (e n (atake test c)
                       {:status :ok, :key "a", :opaque @n, :sq 2, :val "AA"}
                       (atake test c)))
                  (let [c (store/store-set test s @n "a" 0 "AAA" :replace)]
                    (e n (atake test c)
                       {:status :ok, :key "a", :opaque @n, :sq 3}
                       (atake test c)))
                  (let [c (store/store-get test s @n "a")]
                    (e n (atake test c)
                       {:status :ok, :key "a", :opaque @n, :sq 3, :val "AAA"}
                       (atake test c)))
                  (let [c (store/store-set test s @n "a" 0 "suffix" :append)]
                    (e n (atake test c)
                       {:status :ok, :key "a", :opaque @n, :sq 4}
                       (atake test c)))
                  (let [c (store/store-get test s @n "a")]
                    (e n (atake test c)
                       {:status :ok, :key "a", :opaque @n, :sq 4, :val "AAAsuffix"}
                       (atake test c)))
                  (let [c (store/store-set test s @n "a" 0 "prefix" :prepend)]
                    (e n (atake test c)
                       {:status :ok, :key "a", :opaque @n, :sq 5}
                       (atake test c)))
                  (let [c (store/store-get test s @n "a")]
                    (e n (atake test c)
                       {:status :ok, :key "a", :opaque @n, :sq 5, :val "prefixAAAsuffix"}
                       (atake test c)))
                  (let [c (store/store-set test s @n "a" 666 "" :set)]
                    (e n (atake test c)
                       {:status :wrong-cas, :key "a", :opaque @n}
                       (atake test c)))
                  (let [c (store/store-set test s @n "a" 666 "" :add)]
                    (e n (atake test c)
                       {:status :wrong-cas, :key "a", :opaque @n}
                       (atake test c)))
                  (let [c (store/store-set test s @n "a" 666 "" :replace)]
                    (e n (atake test c)
                       {:status :wrong-cas, :key "a", :opaque @n}
                       (atake test c)))
                  (let [c (store/store-set test s @n "a" 666 "" :append)]
                    (e n (atake test c)
                       {:status :wrong-cas, :key "a", :opaque @n}
                       (atake test c)))
                  (let [c (store/store-set test s @n "a" 666 "" :prepend)]
                    (e n (atake test c)
                       {:status :wrong-cas, :key "a", :opaque @n}
                       (atake test c)))
                  (let [c (store/store-del test s @n "a" 666)]
                    (e n (atake test c)
                       {:status :wrong-cas, :key "a", :opaque @n}
                       (atake test c)))
                  (let [gc (store/store-get test s @n "a")
                        gv (atake test gc)
                        _ (atake test gc)
                        sc (store/store-set test s @n "a" (:cas gv) "AAAA" :set)
                        sv (atake test sc)]
                    (and (e n sv
                            {:status :ok, :key "a", :opaque @n, :sq 6, :cas (:cas sv)}
                            (atake test sc))
                         (let [gc2 (store/store-get test s @n "a")]
                           (e n (atake test gc2)
                              {:status :ok, :key "a", :opaque @n, :sq 6, :cas (:cas sv)
                               :val "AAAA"}
                              (atake test gc2)))))
                  (let [gc (store/store-get test s @n "a")
                        gv (atake test gc)
                        _ (atake test gc)
                        sc (store/store-set test s @n "a" (:cas gv) "AAAAA" :replace)
                        sv (atake test sc)]
                    (and (e n sv
                            {:status :ok, :key "a", :opaque @n, :sq 7, :cas (:cas sv)}
                            (atake test sc))
                         (let [gc2 (store/store-get test s @n "a")]
                           (e n (atake test gc2)
                              {:status :ok, :key "a", :opaque @n, :sq 7, :cas (:cas sv)
                               :val "AAAAA"}
                              (atake test gc2)))))
                  (let [gc (store/store-get test s @n "a")
                        gv (atake test gc)
                        _ (atake test gc)
                        sc (store/store-set test s @n "a" (:cas gv) "suffix" :append)
                        sv (atake test sc)]
                    (and (e n sv
                            {:status :ok, :key "a", :opaque @n, :sq 8, :cas (:cas sv)}
                            (atake test sc))
                         (let [gc2 (store/store-get test s @n "a")]
                           (e n (atake test gc2)
                              {:status :ok, :key "a", :opaque @n, :sq 8, :cas (:cas sv)
                               :val "AAAAAsuffix"}
                              (atake test gc2)))))
                  (let [gc (store/store-get test s @n "a")
                        gv (atake test gc)
                        _ (atake test gc)
                        sc (store/store-set test s @n "a" (:cas gv) "prefix" :prepend)
                        sv (atake test sc)]
                    (and (e n sv
                            {:status :ok, :key "a", :opaque @n, :sq 9, :cas (:cas sv)}
                            (atake test sc))
                         (let [gc2 (store/store-get test s @n "a")]
                           (e n (atake test gc2)
                              {:status :ok, :key "a", :opaque @n, :sq 9, :cas (:cas sv)
                               :val "prefixAAAAAsuffix"}
                              (atake test gc2)))))
                  (let [gc (store/store-get test s @n "a")
                        gv (atake test gc)
                        _ (atake test gc)
                        dc (store/store-del test s @n "a" (:cas gv))
                        dv (atake test dc)]
                    (and (e n dv
                            {:status :ok, :key "a", :opaque @n, :sq 10}
                            (atake test dc))
                         (let [gc2 (store/store-get test s @n "a")]
                           (e n (atake test gc2)
                              {:status :not-found, :key "a", :opaque @n}
                              (atake test gc2))))))
           "pass"
           (str "FAIL: on test #" @n)))))
