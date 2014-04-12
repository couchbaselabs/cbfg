(ns cbfg.zgo.core
  (:gen-class)
  (:require [clojure.tools.reader :as r]
            [clojure.tools.reader.reader-types :as rt]))

(defn prm [x]
  (binding [*print-meta* true]
    (pr x)))

(defn read-file [fname]
  (let [ipbr (rt/indexing-push-back-reader
              (java.io.PushbackReader. (clojure.java.io/reader fname)) 10 fname)]
    (binding [*read-eval* false]
      (let [r (r/read ipbr false :eof)]
        (prm r))
      (let [r (r/read ipbr false :eof)]
        (prm r))
      (let [r (r/read ipbr false :eof)]
        (prm r))
      (let [r (r/read ipbr false :eof)]
        (prm r))
      (let [r (r/read ipbr false :eof)]
        (prm r))
        )))

(defn -main
  "some docstring"
  []
  (println "hello world from zgo")
  (read-file "src/cbfg/misc.cljs"))


