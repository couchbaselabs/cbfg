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
  (if (> width 0)
    (format (str "%-" width "s") s)
    s))

(defn nl [lvl] (ljust lvl "\n")) ; Generate newline + indentation.

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

(defn cvt-body [lvl scope forms]
  (map (fn [form] [(cvt-expr (inc lvl) scope form) ";"])
       forms))

(defn cvt-cond [lvl scope op test-expr-forms]
  [(interpose "else"
              (map (fn [[test expr]]
                     ["if (" (cvt-expr (inc lvl) scope test) ") {" (nl lvl)
                      (cvt-expr (inc lvl) scope expr) "}" (nl lvl)])
                   (partition 2 test-expr-forms)))])

(defn cvt-if [lvl scope op [test then else]]
  ["if (" (cvt-expr (inc lvl) scope test) ") {" (nl lvl)
   (cvt-expr (inc lvl) scope then)
   (nl lvl) "} else {" (nl lvl)
   (cvt-expr (inc lvl) scope else)
   (nl lvl) "}"])

(defn cvt-let [lvl scope op [bindings & body]]
  [(interpose (nl lvl)
              (map (fn [[var-name init-val]]
                     [(cvt-sym var-name) ":= (" (cvt-expr (inc lvl) scope init-val) ")"])
                   (partition 2 bindings)))
   (nl lvl)
   (cvt-body (inc lvl) scope body)])

(defn cvt-recur [lvl scope op args]
  [(interpose (nl lvl)
              (map-indexed (fn [idx arg]
                             [(str "l" idx) "=" (cvt-expr (inc lvl) scope arg)])
                           args))
   (nl lvl)
   "continue"])

(defn cvt-ago [lvl scope op [self-actx actx body]]
  ["go {" body "}"])

(defn cvt-ago-loop [lvl scope op [self-actx parent-actx bindings & body]]
  ["(func() chan interface{} { result := make(chan interface{}, 1)" (nl lvl)
   "go (func() {" (nl lvl)
   (interpose (nl lvl)
              (map-indexed (fn [idx [var-name init-val]]
                             [(str "l" idx) ":=" (cvt-expr (inc lvl) scope init-val)])
                           (partition 2 bindings)))
   (nl lvl)
   "for {" (nl lvl)
   (interpose (nl lvl)
              (map-indexed (fn [idx [var-name init-val]]
                             [(cvt-sym var-name) ":=" (str "l" idx)])
                           (partition 2 bindings)))
   (nl lvl)
   (cvt-body (inc lvl) scope body)
   (nl lvl) "}})()" (nl lvl)
   "return result })()"])

(defn make-cvt-infix [op-after]
  (fn [lvl scope op args]
    ["("
     (interpose [")" op-after "("]
                (map (fn [arg] (cvt-expr (inc lvl) scope arg))
                     args))
     ")"]))

(def cvt-special-fns
  {"ago"      cvt-ago
   "ago-loop" cvt-ago-loop
   "cond"  cvt-cond
   "if"    cvt-if
   "let"   cvt-let
   "recur" cvt-recur
   "="     (make-cvt-infix "==")})

(defn cvt-normal-fn [lvl scope name args]
  [name "(" args ")"])

(defn cvt-expr [lvl scope expr]
  (cond
   (vector? expr) ["[" (interpose "," (map #(cvt-expr (inc lvl) scope %) expr)) "]"]
   (map? expr) ["{" (interpose "," (map (fn [[k v]]
                                          [(cvt-expr (inc lvl) scope k) ":"
                                           (cvt-expr (inc lvl) scope v)])
                                        expr)) "}"]
   (set? expr) expr
   (coll? expr) (let [[fn-name & args] expr
                      fn-handler (get cvt-special-fns (str fn-name)
                                      cvt-normal-fn)]
                  (fn-handler lvl scope fn-name args))
   (symbol? expr) (cvt-sym expr)
   (keyword? expr) (str expr)
   (nil? expr) "nil"
   :default ["DEFAULT-EXPR" expr]))

(defn cvt-fn-params [params]
  ["(" (interpose "," (map (fn [sym] [(cvt-sym sym) (cvt-type sym)])
                           params))
   ")"])

(defn cvt-fn-body [lvl params forms]
  (map-indexed (fn [idx form]
                 [(when (= idx (dec (count forms))) "return")
                  (cvt-expr (inc lvl) params form) ";"])
               forms))

(defn cvt-fn [lvl params forms]
  ["func" (cvt-fn-params params) "{" (nl lvl)
   (cvt-fn-body (inc lvl) params forms) "}" (nl lvl)])

(defn cvt-defn [name params forms]
  ["func" (cvt-sym name) (rest (cvt-fn 0 params forms))])

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

