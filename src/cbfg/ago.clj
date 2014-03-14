(ns cbfg.ago
  (:require [cljs.core.async.macros :refer [go]]))

(defmacro actx-top [actx]
  `(first ~actx))

(defmacro ago [child-actx-binding-name actx & body]
  `(let [w# (actx-top ~actx)
         ago-id# (swap! (:last-id w#) inc)
         ~child-actx-binding-name (conj ~actx
                                        ['~child-actx-binding-name ago-id#])]
     (go ~@body)))

(defmacro ago-loop [child-actx-binding-name actx bindings & body]
  `(ago ~child-actx-binding-name ~actx (loop ~bindings ~@body)))

(defmacro achan [actx]
  `(let [ch# (cljs.core.async/chan)
         w# (actx-top ~actx)]
     (println "achan" ~actx ch#)
     (swap! (:tot-chs w#) inc)
     (swap! (:chs w#) assoc ch# [])
     ch#))

(defmacro achan-buf [actx buf-or-size]
  `(do (println "achan-buf" ~actx ~buf-or-size)
       (cljs.core.async/chan ~buf-or-size)))

(defmacro aclose [actx ch]
  `(do (println "aclose" ~actx)
       (cljs.core.async/close! ~ch)))

(defmacro atake [actx ch]
  `(do (println (cljs.core.async/<! (:tick-ch (actx-top ~actx))) "atake" ~actx ~ch)
       (cljs.core.async/<! ~ch)))

(defmacro aput [actx ch msg]
  `(do (println (cljs.core.async/<! (:tick-ch (actx-top ~actx))) "aput" ~actx ~msg)
       (cljs.core.async/>! ~ch ~msg)))

(defmacro aalts [actx chs]
  `(cljs.core.async/alts! ~chs))