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

(defn process-top-level-form [smodel form]
  (let [[op name & rest] form]
    (case (str op)
      "ns" [["package" name] (line-range form)]
      "defn" [["func" name] (line-range form)]
      "UNKNOWN-TOP-LEVEL-FORM")))

(defn process-top-level-forms [smodel]
  (map #(process-top-level-form smodel %)
       (:forms smodel)))

(defn numbered-lines [lines beg end] ; beg and end are 1-based.
  (map vector
       (range beg (inc end))
       (subvec lines (dec beg) end)))

(defn do-lines [lines beg end f] ; beg and end are 1-based.
  (doseq [[i curr-line] (numbered-lines lines beg end)]
    (f i curr-line)))

(defn emit [slines pmodel-in]
  (loop [last-line 0
         pmodel pmodel-in]
    (when (seq pmodel)
      (let [[out [sline-beg sline-end]] (first pmodel)]
        (when (> sline-beg (inc last-line))
          (do-lines slines (inc last-line) (dec sline-beg)
                    #(println "//" %1 %2)))
        (do-lines slines sline-beg sline-end
                  #(println "//" %1 %2))
        (println "out>>>" out)
        (recur sline-end
               (rest pmodel))))))

(defn -main
  "some docstring"
  []
  (println "hello world from zgo")
  (let [smodel (read-model-file "src/cbfg/fence.cljs")
        pmodel (process-top-level-forms smodel)]
    (pprintm smodel)
    (pprintm pmodel)
    (emit (:lines smodel) pmodel)))

