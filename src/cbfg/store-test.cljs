(ns cbfg.store-test
  (:require-macros [cbfg.ago :refer [achan aclose ago atake]])
  (:require [cljs.core.async :refer [into]]
            [cbfg.store :as store]))

(defn e [num result expect]
  (println (swap! num inc) ":"
           (if (= result expect) "pass" (str "FAIL:" result expect)))
  (= result expect))

(defn test [actx opaque-id]
  (let [num (atom 0)
        s (store/make-store)]
    (ago test actx
         (if (and (let [ch (store/store-get test s 1 "does-not-exist")]
                    (e num (atake test ch)
                       {:status :not-found, :key "does-not-exist", :opaque 1})))
           "pass"
           (str "FAIL: on test #" @num)))))
