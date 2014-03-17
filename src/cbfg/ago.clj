;; Wrappers around core.async that track parent-child relationships
;; and provide events.  Useful for building things like visualizations
;; and simulations.

(ns cbfg.ago
  (:require [cljs.core.async.macros :refer [go]]))

(defmacro actx-top [actx]
  `(first ~actx))

(defmacro actx-event [actx event]
  `(cljs.core.async/>! (:event-ch (actx-top ~actx)) [~actx ~event]))

(defmacro ago [child-actx-binding-name actx & body]
  `(let [ago-id# ((:gen-id (actx-top ~actx)))
         ~child-actx-binding-name (conj ~actx
                                        ['~child-actx-binding-name ago-id#])]
     (go (actx-event ~actx ["ago" :beg ~child-actx-binding-name])
         (let [result# (do ~@body)]
           (actx-event ~actx ["ago" :end ~child-actx-binding-name result#])
           result#))))

(defmacro ago-loop [child-actx-binding-name actx bindings & body]
  `(ago ~child-actx-binding-name ~actx (loop ~bindings ~@body)))

(defmacro achan [actx]
  `(achan-buf ~actx 0))

(defmacro achan-buf [actx buf-or-size]
  `(cljs.core.async/chan)) ; No event since might be outside go block.

(defmacro aclose [actx ch]
  `(do (actx-event ~actx ["aclose" :beg ~ch])
       (let [result# (cljs.core.async/close! ~ch)]
         (actx-event ~actx ["aclose" :end ~ch result#])
         result#)))

(defmacro atake [actx ch]
  `(do (actx-event ~actx ["atake" :beg ~ch])
       (let [result# (cljs.core.async/<! ~ch)]
         (actx-event ~actx ["atake" :end ~ch result#])
         result#)))

(defmacro aput [actx ch msg]
  `(do (actx-event ~actx ["aput" :beg ~ch ~msg])
       (let [result# (cljs.core.async/>! ~ch ~msg)]
         (actx-event ~actx ["aput" :end ~ch ~msg result#])
         result#)))

(defmacro aalts [actx chs]
  `(do (actx-event ~actx ["aalts" :beg ~chs])
       (let [result# (cljs.core.async/alts! ~chs)]
         (actx-event ~actx ["aalts" :end ~chs result#])
         result#)))
