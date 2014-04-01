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
    (and pass (not result-nil))))

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
                       (atake test c))))
           "pass"
           (str "FAIL: on test #" @n)))))
