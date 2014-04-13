(ns cbfg.zgo.core
  (:gen-class)
  (:require [clojure.string :as string]
            [clojure.tools.reader :as r]
            [clojure.tools.reader.reader-types :as rt]
            [clojure.pprint :refer [pprint]]))

(def OUT-WIDTH 50)

(defn word-wrap [width s] ; Returns a sequence of strings from word-wrapping s.
  (re-seq (re-pattern (str ".{0," width "}\\s")) s))

(defn ljust [width s] ; Left justify a string.
  (format (str "%-" width "s:") s))

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

(defn line-range [form] ; Returns the source [line-beg line-end] nums for a form.
  (if (coll? form)
    (reduce (fn [[min-line-beg max-line-end] v]
              (let [[line-beg line-end] (line-range v)]
                [(min min-line-beg line-beg)
                 (max max-line-end line-end)]))
            [MAX-LINE MIN-LINE]
            form)
    [(or (:line (meta form)) MAX-LINE)
     (or (:end-line (meta form)) MIN-LINE)]))

(defn j [v] (string/join " " (flatten v)))

(defn c2g-sym [sym]
  (-> (str sym)
      (string/replace "-" "_")
      (string/replace "?" "p")))

(defn c2g-type [sym]
  (let [parts (string/split (str sym) #"-")
        plast (last parts)
        [p0 & prest] parts]
    (cond
     (= p0 "name") "string"
     (= p0 "max") "int"
     (= p0 "min") "int"
     (re-find #"\?$" plast) "bool" ; When ends with '?'.
     :default (string/capitalize plast))))

(defn process-fn-params [params]
  ["(" (interpose "," (map (fn [sym] [(c2g-sym sym) (c2g-type sym)])
                           params)) ")"])

(defn process-fn-body [params forms]
  (map-indexed (fn [idx form]
                 [(when (= idx (dec (count forms))) "return")
                  form ";"])
               forms))

(defn process-fn [params body]
  ["func" (process-fn-params params) "{" (process-fn-body params body) "}"])

(defn process-defn [name params body]
  ["func" (c2g-sym name) (rest (process-fn params body))])

(defn process-top-level-form [smodel form]
  (let [[op name & more] form]
    (case (str op)
      "ns" [(j ["package" name]) (line-range form)]
      "defn" [(j (process-defn name (first more) (rest more))) (line-range form)]
      "UNKNOWN-TOP-LEVEL-FORM")))

(defn process-top-level-forms [smodel]
  (map #(process-top-level-form smodel %)
       (:forms smodel)))

(defn numbered-lines [lines beg end] ; beg and end are 1-based.
  (map vector
       (range beg (inc end))
       (subvec lines (dec beg) end)))

(defn emit-merged-lines [out numbered-slines]
  (loop [olines (word-wrap (dec OUT-WIDTH) out)
         slines numbered-slines]
    (let [oline (first olines)
          [i sline] (first slines)]
      (when (or oline sline)
        (do (println (ljust OUT-WIDTH (or oline ""))
                     (when sline (str "// " i " " sline)))
            (recur (rest olines) (rest slines)))))))

(defn emit [slines pmodel-in]
  (loop [last-line 0
         pmodel pmodel-in]
    (when (seq pmodel)
      (let [[out [sline-beg sline-end]] (first pmodel)]
        (when (> sline-beg (inc last-line))
          (doseq [[i line] (numbered-lines slines
                                           (inc last-line)
                                           (dec sline-beg))]
            (println "//" i line)))
        (emit-merged-lines out (numbered-lines slines
                                               sline-beg
                                               sline-end))
        (recur sline-end
               (rest pmodel))))))

(defn -main
  "some docstring"
  []
  (println "hello world from zgo")
  (let [smodel (read-model-file "src/cbfg/fence.cljs")
        pmodel (process-top-level-forms smodel)]
    (pprint smodel)
    (emit (:lines smodel) pmodel)))

