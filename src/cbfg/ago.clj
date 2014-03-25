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
  `(let [ago-ch# (achan-buf ~actx 1)
         ago-id# ((:gen-id (actx-top ~actx)))
         ~child-actx-binding-name (conj ~actx
                                        (str '~child-actx-binding-name "-" ago-id#))]
     (go (actx-event ~actx [:ago :start ~child-actx-binding-name])
         (let [result# (do ~@body)]
           (when result#
             (aput ~child-actx-binding-name ago-ch# result#))
           (aclose ~child-actx-binding-name ago-ch#)
           (actx-event ~actx [:ago :end ~child-actx-binding-name result#])
           result#))
     ago-ch#))

(defmacro ago-loop [child-actx-binding-name actx bindings & body]
  (let [m (->> bindings
               (partition 2)
               (map first)
               (mapcat (fn [sym] [(keyword (name sym)) sym])))]
    `(ago ~child-actx-binding-name ~actx
          (loop ~bindings
            (actx-event ~child-actx-binding-name
                        [:ago-loop :loop-state (sorted-map ~@m)])
            ~@body))))

(defmacro achan [actx]
  `(achan-buf ~actx 0))

(defmacro achan-buf [actx buf-or-size]
  `(cljs.core.async/chan)) ; No event since might be outside go block.

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
  `(do (actx-event ~actx [:aput :before '~ch ~ch ~msg])
       (let [result# (cljs.core.async/>! ~ch ~msg)]
         (actx-event ~actx [:aput :after '~ch ~ch ~msg result#])
         result#)))

(defmacro aalts [actx chs]
  `(do (actx-event ~actx [:aalts :before ~chs])
       (let [result# (cljs.core.async/alts! ~chs)]
         (actx-event ~actx [:aalts :after ~chs result#])
         result#)))
