(ns cbfg.zgo.core
  (:gen-class)
  (:require [clojure.tools.reader :as r]
            [clojure.tools.reader.reader-types :as rt]
            [clojure.pprint :refer [pprint]]))

(defn pprintm [x]
  (binding [*print-meta* true]
    (pprint x)))

(defn read-model-file [fname]
    {:fname fname
     :forms (let [rdr (rt/indexing-push-back-reader
                       (java.io.PushbackReader. (clojure.java.io/reader fname))
                       10 fname)]
              (binding [*read-eval* false]
                (loop [forms []]
                  (let [form (r/read rdr false :eof)]
                    (if (= form :eof)
                      forms
                      (recur (conj forms form)))))))
     :lines (with-open [rdr (clojure.java.io/reader fname)]
              (vec (line-seq rdr)))})

(def MAX-LINE 0x8FFFFFF)
(def MIN-LINE -1)

(defn line-range [form]
  (if (coll? form)
    (reduce (fn [[min-line-beg max-line-end] v]
              (let [[line-beg line-end] (line-range v)]
                [(min min-line-beg line-beg)
                 (max max-line-end line-end)]))
            [MAX-LINE MIN-LINE]
            form)
    [(or (:line (meta form)) MAX-LINE)
     (or (:end-line (meta form)) MIN-LINE)]))

(defn process-top-level-form [model form]
  (let [[op name & rest] form]
    (case (str op)
      "ns" ["package" name (line-range form)]
      "defn" ["func" name (line-range form)]
      "UNKNOWN-TOP-LEVEL-FORM")))

(defn process-top-level-forms [model]
  (map #(process-top-level-form model %)
       (:forms model)))

(defn -main
  "some docstring"
  []
  (println "hello world from zgo")
  (let [model (read-model-file "src/cbfg/fence.cljs")
        pmodel (process-top-level-forms model)]
    (pprintm model)
    (pprintm pmodel)))

