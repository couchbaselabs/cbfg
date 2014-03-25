;; Browser and UI-related stuff goes in this file.
;; generic stuff should go elsewhere.

(ns cbfg.core
  (:require-macros [cljs.core.async.macros :refer [go go-loop]]
                   [cbfg.ago :refer [ago ago-loop achan-buf aput atake]])
  (:require [clojure.string :as string]
            [cljs.core.async :refer [<! >! put! chan timeout merge map< dropping-buffer]]
            [goog.dom :as gdom]
            [goog.events :as gevents]
            cbfg.ddl
            cbfg.world-example0))

(enable-console-print!)

(println (.-href (.-location js/window)))

(println cbfg.ddl/hi)

