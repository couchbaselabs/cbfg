;; These "act" wrappers around ago provide parent-child tracking and
;; event hooks.  Useful for building things like visualizations and
;; simulations.  (ago enhances core.async with snapshots/rollback.)

(ns cbfg.act
  (:require [cljs.core.async.macros :refer [go]]
            [ago.macros :refer [ago]]))

(defmacro actx-top [actx]
  `(first ~actx))

(defmacro actx-agw [actx]
  `((:get-agw (actx-top ~actx)))) ; The 'agw' means 'ago world'.

(defmacro actx-event [actx loc event]
  `(cljs.core.async/>! (:event-ch (actx-top ~actx)) [~actx ~loc ~event]))

(defmacro act [child-actx-binding-name actx & body] ; Use 'act' instead of 'go'.
  (let [loc [(name cljs.analyzer/*cljs-ns*) (meta &form)]]
    `(let [act-ch# (achan-buf ~actx 1)
           act-id# ((:gen-id (actx-top ~actx)))
           ~child-actx-binding-name (conj ~actx
                                          (str '~child-actx-binding-name "-" act-id#))]
       (ago (actx-agw ~actx)
            (actx-event ~actx ~loc [:act :start ~child-actx-binding-name])
            (let [result# (do ~@body)]
              (when result#
                (aput ~child-actx-binding-name act-ch# result#))
              (aclose ~child-actx-binding-name act-ch#)
              (actx-event ~actx ~loc [:act :end ~child-actx-binding-name result#])
              result#))
       act-ch#)))

(defmacro act-loop [child-actx-binding-name actx bindings & body]
  ; Use 'act-loop' instead of 'go-loop'.
  (let [m (->> bindings
               (partition 2)
               (map first)
               (mapcat (fn [sym] [(keyword (name sym)) sym])))
        loc [(name cljs.analyzer/*cljs-ns*) (meta &form)]]
    `(act ~child-actx-binding-name ~actx
          (loop ~bindings
            (actx-event ~child-actx-binding-name ~loc
                        [:act-loop :loop-state (sorted-map ~@m)])
            ~@body))))

(defmacro achan [actx] ; Use 'achan' instead of 'chan'.
  `(achan-buf ~actx nil))

(defmacro achan-buf [actx buf-or-size]
  ; No event since might be outside go block.
  `(ago.core/ago-chan (actx-agw ~actx) ~buf-or-size))

(defmacro aclose [actx ch]
  (let [loc [(name cljs.analyzer/*cljs-ns*) (meta &form)]]
    `(do (actx-event ~actx ~loc [:aclose :before ~ch])
         (let [result# (cljs.core.async/close! ~ch)]
           (actx-event ~actx ~loc [:aclose :after ~ch result#])
           result#))))

(defmacro atake [actx ch]
  (let [loc [(name cljs.analyzer/*cljs-ns*) (meta &form)]]
    `(do (actx-event ~actx ~loc [:atake :before '~ch ~ch])
         (let [result# (cljs.core.async/<! ~ch)]
           (actx-event ~actx ~loc [:atake :after '~ch ~ch result#])
           result#))))

(defmacro aput [actx ch msg]
  (let [loc [(name cljs.analyzer/*cljs-ns*) (meta &form)]]
    `(let [msg# ~msg]
       (actx-event ~actx ~loc [:aput :before '~ch ~ch msg#])
       (let [result# (cljs.core.async/>! ~ch msg#)]
         (actx-event ~actx ~loc [:aput :after '~ch ~ch msg# result#])
         result#))))

(defmacro aalts [actx chs]
  (let [loc [(name cljs.analyzer/*cljs-ns*) (meta &form)]]
    `(do (actx-event ~actx ~loc [:aalts :before '~chs ~chs])
         (let [result# (cljs.core.async/alts! ~chs)]
           (actx-event ~actx ~loc [:aalts :after '~chs ~chs result#])
           result#))))

(defmacro atimeout [actx delay]
  `((:make-timeout-ch (actx-top ~actx)) ~actx ~delay))

(defmacro aput-close [actx ch msg]
  `(let [msg# ~msg]
     (aput ~actx ~ch msg#)
     (aclose ~actx ~ch)))

(defmacro areq [actx ch msg]
  `(let [res-ch# (achan ~actx)]
     (if (aput ~actx ~ch (assoc ~msg :res-ch res-ch#))
       (atake ~actx res-ch#)
       (assoc ~msg :status :failed :status-info [(:op ~msg) :closed :areq]))))
