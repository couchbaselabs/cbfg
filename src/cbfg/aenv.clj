(ns cbfg.aenv
    (:require [cljs.core.async.macros :refer [go go-loop]]))

(defmacro ago-loop [ainfo bindings & body]
  `(go (loop ~bindings ~@body)))

