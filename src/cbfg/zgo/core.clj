(ns cbfg.zgo.core
  (:gen-class)
  (:require [clojure.tools.reader :as r]
            [clojure.tools.reader.reader-types :as rt]
            [clojure.pprint :refer [pprint]]))

(defn prm [x]
  (binding [*print-meta* true]
    (pr x)))

(defn read-model-file-rdr [fname rdr]
  (binding [*read-eval* false]
    (loop [forms []]
      (let [form (r/read rdr false :eof)]
        (if (= form :eof)
          forms
          (recur (conj forms form)))))))

(defn read-model-file [fname]
    {:forms (let [rdr (rt/indexing-push-back-reader
                       (java.io.PushbackReader. (clojure.java.io/reader fname)) 10 fname)]
              (binding [*read-eval* false]
                (loop [forms []]
                  (let [form (r/read rdr false :eof)]
                    (if (= form :eof)
                      forms
                      (recur (conj forms form)))))))
     :lines (with-open [rdr (clojure.java.io/reader fname)]
              (vec (line-seq rdr)))})

(defn -main
  "some docstring"
  []
  (println "hello world from zgo")
  (pprint (read-model-file "src/cbfg/misc.cljs")))


