(ns cbfg.world-base
  (:require-macros [cbfg.ago :refer [ago aclose achan aput atake atimeout]]))

(defn example-add [actx c]
  (ago example-add actx
       (let [timeout-ch (atimeout example-add (:delay c))]
         (atake example-add timeout-ch)
         (assoc c :result (+ (:x c) (:y c))))))

(defn example-sub [actx c]
  (ago example-sub actx
       (let [timeout-ch (atimeout example-sub (:delay c))]
         (atake example-sub timeout-ch)
         (assoc c :result (- (:x c) (:y c))))))

(defn example-count [actx c]
  (let [out (achan actx)]
    (ago example-count actx
         (doseq [n (range (:x c) (:y c))]
           (let [timeout-ch (atimeout example-count (:delay c))]
             (atake example-count timeout-ch))
           (aput example-count out (assoc c :partial n)))
         (let [timeout-ch (atimeout example-count (:delay c))]
           (atake example-count timeout-ch))
         (aput example-count out (assoc c :result (:y c)))
         (aclose example-count out))
    out))
