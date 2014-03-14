(ns cbfg.ago
  (:require [cljs.core.async.macros :refer [go]]))

(defmacro actx-top [actx]
  `(first ~actx))

(defmacro actx-event [actx event]
  (cljs.core.async/>! (:event-ch (actx-top ~actx)) ~event))

(defmacro ago [child-actx-binding-name actx & body]
  `(let [w# (actx-top ~actx)
         ago-id# (swap! (:last-id w#) inc)
         ~child-actx-binding-name (conj ~actx
                                        ['~child-actx-binding-name ago-id#])]
     (go (actx-event ~actx ["ago" ~child-actx-binding-name])
         ~@body)))

(defmacro ago-loop [child-actx-binding-name actx bindings & body]
  `(ago ~child-actx-binding-name ~actx (loop ~bindings ~@body)))

(defmacro achan [actx]
  `(achan-buf ~actx 0))

(defmacro achan-buf [actx buf-or-size]
  `(let [ch# (cljs.core.async/chan)
         w# (actx-top ~actx)]
     (println "achan-buf"
              ~actx ~buf-or-size ch#) ; No event since might be outside go block.
     (swap! (:tot-chs w#) inc)
     (swap! (:chs w#) assoc ch# [])
     ch#))

(defmacro aclose [actx ch]
  `(let [w# (actx-top ~actx)]
     (actx-event ~actx ["aclose" ~actx ~ch])
     (swap! (:chs w#) dissoc ch#)
     (cljs.core.async/close! ~ch)))

(defmacro atake [actx ch]
  `(do (actx-event ~actx ["atake" ~actx ~ch])
       (cljs.core.async/<! ~ch)))

(defmacro aput [actx ch msg]
  `(do (actx-event ~actx ["aput" ~actx ~msg])
       (cljs.core.async/>! ~ch ~msg)))

(defmacro aalts [actx chs]
  `(do (actx-event ~actx ["aalts" ~actx ~chs])
       (cljs.core.async/alts! ~chs)))