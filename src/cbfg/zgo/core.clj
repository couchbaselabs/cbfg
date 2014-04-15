(ns cbfg.zgo.core
  (:gen-class)
  (:require [clojure.string :as string]
            [clojure.tools.reader :as r]
            [clojure.tools.reader.reader-types :as rt]
            [clojure.pprint :refer [pprint]]))

(def OUT-WIDTH 80)

(defn word-wrap [width s] ; Returns a sequence of strings from word-wrapping s.
  (re-seq (re-pattern (str ".{0," width "}\\s")) (str s " ")))

(defn ljust [width s] ; Left justify a string.
  (format (str "%-" width "s") s))

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

(defn cvt-sym [sym]
  (-> (str sym)
      (string/replace "." "_")
      (string/replace "-" "_")
      (string/replace "?" "p")))

(defn cvt-type [sym]
  (let [parts (string/split (str sym) #"-")
        plast (last parts)
        [p0 & prest] parts]
    (cond
     (= p0 "name") "string"
     (= p0 "max") "int"
     (= p0 "min") "int"
     (re-find #"\?$" plast) "bool" ; When ends with '?'.
     :default (string/capitalize plast))))

(declare cvt-expr)

(defn cvt-body [scope forms]
  (map (fn [form] [(cvt-expr scope form) ";"])
       forms))

(defn cvt-let [scope op [bindings & body]]
  [(interpose "\n"
              (map (fn [[var-name init-val]]
                     [(cvt-sym var-name) ":= (" (cvt-expr scope init-val) ")"])
                   (partition 2 bindings)))
   "\n"
   (cvt-body scope body)])

(defn cvt-if [scope op [test then else]]
  ["if (" (cvt-expr scope test) ") {\n"
   (cvt-expr scope then)
   "\n} else {\n"
   (cvt-expr scope else)
   "\n}"])

(defn cvt-cond [scope op test-expr-forms]
  [(interpose "else"
              (map (fn [[test expr]]
                     ["if (" (cvt-expr scope test) ") {\n" (cvt-expr scope expr) "\n}"])
                   (partition 2 test-expr-forms)))])

(defn cvt-ago [scope op [self-actx actx body]]
  ["go {" body "}"])

(defn cvt-ago-loop [scope op [self-actx parent-actx bindings & body]]
  ["(func() chan interface{} { result := make(chan interface{}, 1)\n"
   "go (func() {\n"
   (interpose "\n"
              (map-indexed (fn [idx [var-name init-val]]
                             [(str "l" idx) ":=" (cvt-expr scope init-val)])
                           (partition 2 bindings)))
   "\nfor {\n"
   (interpose "\n"
              (map-indexed (fn [idx [var-name init-val]]
                             [(cvt-sym var-name) ":=" (str "l" idx)])
                           (partition 2 bindings)))
   "\n"
   (cvt-body scope body)
   "\n}})()\n"
   "return result })()"])

(def cvt-special-fns
  {"ago"      cvt-ago
   "ago-loop" cvt-ago-loop
   "cond" cvt-cond
   "if"   cvt-if
   "let"  cvt-let})

(defn cvt-normal-fn [scope name args]
  [name "(" args ")"])

(defn cvt-expr [scope expr]
  (cond
   (vector? expr) ["[" (interpose "," (map #(cvt-expr scope %) expr)) "]"]
   (map? expr) ["{" (interpose "," (map (fn [[k v]]
                                          [(cvt-expr scope k) ":"
                                           (cvt-expr scope v)])
                                        expr)) "}"]
   (set? expr) expr
   (coll? expr) (let [[fn-name & args] expr
                      fn-handler (get cvt-special-fns (str fn-name)
                                      cvt-normal-fn)]
                  (fn-handler scope fn-name args))
   (symbol? expr) (cvt-sym expr)
   (keyword? expr) (str expr)
   (nil? expr) "nil"
   :default ["DEFAULT-EXPR" expr]))

(defn cvt-fn-params [params]
  ["(" (interpose "," (map (fn [sym] [(cvt-sym sym) (cvt-type sym)])
                           params))
   ")"])

(defn cvt-fn-body [params forms]
  (map-indexed (fn [idx form]
                 [(when (= idx (dec (count forms))) "return")
                  (cvt-expr params form) ";"])
               forms))

(defn cvt-fn [params body]
  ["func" (cvt-fn-params params) "{\n" (cvt-fn-body params body) "}\n"])

(defn cvt-defn [name params body]
  ["func" (cvt-sym name) (rest (cvt-fn params body))])

(defn cvt-top-level-form [form]
  (let [[op name & more] form]
    [(case (str op)
      "ns" (j ["package" (cvt-sym name)])
      "defn" (j (cvt-defn name (first more) (rest more)))
      "UNKNOWN-TOP-LEVEL-FORM")
     (line-range form)]))

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
        (do (println (ljust OUT-WIDTH (string/replace (or oline "") "\n" ""))
                     (if sline (str "// " i " " sline) ""))
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

(defn -main []
  (let [smodel (read-model-file "src/cbfg/fence.cljs")
        pmodel (map cvt-top-level-form (:forms smodel))]
    (emit (:lines smodel) pmodel)))

