(ns cbfg.ago
  (:require [cljs.core.async.macros :refer [go]]))

(defmacro actx-top [actx]
  `(first ~actx))

(defmacro actx-event [actx event]
  `(cljs.core.async/>! (:event-ch (actx-top ~actx)) ~event))

(defmacro ago [child-actx-binding-name actx & body]
  `(let [w# (actx-top ~actx)
         ago-id# (swap! (:last-id w#) inc)
         ~child-actx-binding-name (conj ~actx
                                        ['~child-actx-binding-name ago-id#])]
     (go (actx-event ~actx [:beg "ago" ~child-actx-binding-name])
         (let [result# (do ~@body)]
           (actx-event ~actx [:end "ago" ~child-actx-binding-name result#])
           result#))))

(defmacro ago-loop [child-actx-binding-name actx bindings & body]
  `(ago ~child-actx-binding-name ~actx (loop ~bindings ~@body)))

(defmacro achan [actx]
  `(achan-buf ~actx 0))

(defmacro achan-buf [actx buf-or-size]
  `(let [ch# (cljs.core.async/chan)]
     (println "achan-buf"
              ~actx ~buf-or-size ch#) ; No event since might be outside go block.
     ch#))

(defmacro aclose [actx ch]
  `(do (actx-event ~actx [:beg "aclose" ~actx ~ch])
       (let [result# (cljs.core.async/close! ~ch)]
         (actx-event ~actx [:end "aclose" ~actx ~ch result#])
         result#)))

(defmacro atake [actx ch]
  `(do (actx-event ~actx [:beg "atake" ~actx ~ch])
       (let [result# (cljs.core.async/<! ~ch)]
         (actx-event ~actx [:end "atake" ~actx ~ch result#])
         result#)))

(defmacro aput [actx ch msg]
  `(do (actx-event ~actx [:beg "aput" ~actx ~msg])
       (let [result# (cljs.core.async/>! ~ch ~msg)]
         (actx-event ~actx [:end "aput" ~actx ~msg result#])
         result#)))

(defmacro aalts [actx chs]
  `(do (actx-event ~actx [:beg "aalts" ~actx ~chs])
       (let [result# (cljs.core.async/alts! ~chs)]
         (actx-event ~actx [:end "aalts" ~actx ~chs result#])
         result#)))
