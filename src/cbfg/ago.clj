(ns cbfg.ago
    (:require [cljs.core.async.macros :refer [go go-loop]]))

(defmacro ago [aenv-info & body]
  `(go ~@body))

(defmacro ago-loop [aenv-info bindings & body]
  `(go (loop ~bindings ~@body)))

(defmacro aput [aenv ch msg]
  `(cljs.core.async/>! ~ch ~msg))

(defmacro atake [aenv ch]
  `(cljs.core.async/<! ~ch))

(defmacro achan [aenv]
  `(cljs.core.async/chan))

(defmacro achan-buf [aenv buf-or-size]
  `(cljs.core.async/chan ~buf-or-size))

(defmacro aclose [aenv ch]
  `(cljs.core.async/close! ~ch))