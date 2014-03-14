(ns cbfg.ago
  (:require [cljs.core.async.macros :refer [go]]))

(defmacro ago [child-actx-binding-name actx & body]
  `(let [~child-actx-binding-name [~actx '~child-actx-binding-name (gensym)]]
     (go ~@body)))

(defmacro ago-loop [child-actx-binding-name actx bindings & body]
  `(ago ~child-actx-binding-name ~actx (loop ~bindings ~@body)))

(defmacro achan [actx]
  `(do (println "achan" ~actx)
       (cljs.core.async/chan)))

(defmacro achan-buf [actx buf-or-size]
  `(do (println "achan-buf" ~actx ~buf-or-size)
       (cljs.core.async/chan ~buf-or-size))

(defmacro aclose [actx ch]
  `(do (println "aclose" ~actx)
       (cljs.core.async/close! ~ch)))

(defmacro atake [actx ch]
  `(do (println "atake" ~actx)
       (cljs.core.async/<! ~ch)))

(defmacro aput [actx ch msg]
  `(do (println "aput" ~actx)
       (cljs.core.async/>! ~ch ~msg)))

