(ns cbfg.store-test
  (:require-macros [cbfg.ago :refer [achan aclose ago atake]])
  (:require [cljs.core.async :refer [into]]
            [cbfg.store :as store]))

(defn e [n result expect]
  (println (swap! n inc) ":"
           (if (= result expect) "pass" (str "FAIL:" result expect)))
  (= result expect))

(defn test [actx opaque-id]
  (let [n (atom 0)
        s (store/make-store)]
    (ago test actx
         (if (and (e n (atake test (store/store-get test s 1 "does-not-exist"))
                     {:status :not-found, :key "does-not-exist", :opaque 1}))
           "pass"
           (str "FAIL: on test #" @n)))))
