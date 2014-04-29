;; Wrappers around core.async that track parent-child relationships
;; and provide events.  Useful for building things like visualizations
;; and simulations.

(ns cbfg.act
  (:require [cljs.core.async.macros :refer [go]]))

(defmacro actx-top [actx]
  `(first ~actx))

(defmacro actx-event [actx event]
  `(cljs.core.async/>! (:event-ch (actx-top ~actx)) [~actx ~event]))

(defmacro act [child-actx-binding-name actx & body]
  `(let [act-ch# (achan-buf ~actx 1)
         act-id# ((:gen-id (actx-top ~actx)))
         ~child-actx-binding-name (conj ~actx
                                        (str '~child-actx-binding-name "-" act-id#))]
     (go (actx-event ~actx [:act :start ~child-actx-binding-name])
         (let [result# (do ~@body)]
           (when result#
             (aput ~child-actx-binding-name act-ch# result#))
           (aclose ~child-actx-binding-name act-ch#)
           (actx-event ~actx [:act :end ~child-actx-binding-name result#])
           result#))
     act-ch#))

(defmacro act-loop [child-actx-binding-name actx bindings & body]
  (let [m (->> bindings
               (partition 2)
               (map first)
               (mapcat (fn [sym] [(keyword (name sym)) sym])))]
    `(act ~child-actx-binding-name ~actx
          (loop ~bindings
            (actx-event ~child-actx-binding-name
                        [:act-loop :loop-state (sorted-map ~@m)])
            ~@body))))

(defmacro achan [actx]
  `(achan-buf ~actx nil))

(defmacro achan-buf [actx buf-or-size]
  `(cljs.core.async/chan ~buf-or-size)) ; No event since might be outside go block.

(defmacro aclose [actx ch]
  `(do (actx-event ~actx [:aclose :before ~ch])
       (let [result# (cljs.core.async/close! ~ch)]
         (actx-event ~actx [:aclose :after ~ch result#])
         result#)))

(defmacro atake [actx ch]
  `(do (actx-event ~actx [:atake :before '~ch ~ch])
       (let [result# (cljs.core.async/<! ~ch)]
         (actx-event ~actx [:atake :after '~ch ~ch result#])
         result#)))

(defmacro aput [actx ch msg]
  `(let [msg# ~msg]
     (actx-event ~actx [:aput :before '~ch ~ch msg#])
     (let [result# (cljs.core.async/>! ~ch msg#)]
       (actx-event ~actx [:aput :after '~ch ~ch msg# result#])
       result#)))

(defmacro aalts [actx chs]
  `(do (actx-event ~actx [:aalts :before '~chs ~chs])
       (let [result# (cljs.core.async/alts! ~chs)]
         (actx-event ~actx [:aalts :after '~chs ~chs result#])
         result#)))

(defmacro atimeout [actx delay]
  `((:make-timeout-ch (actx-top ~actx)) ~actx ~delay))

(defmacro aput-close [actx ch msg]
  `(let [msg# ~msg]
     (aput ~actx ~ch msg#)
     (aclose ~actx ~ch)))
