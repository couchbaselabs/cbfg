;; Browser and UI-related stuff goes in this file.
;; generic stuff should go elsewhere.

(ns cbfg.core
  (:require-macros [cljs.core.async.macros :refer [go go-loop]]
                   [cbfg.ago :refer [ago ago-loop achan-buf aput atake]])
  (:require [clojure.string :as string]
            [cljs.core.async :refer [<! >! put! chan timeout merge map< dropping-buffer]]
            [goog.dom :as gdom]
            [goog.events :as gevents]
            cbfg.world-example0))

(enable-console-print!)

(def makers {"world-example0.html" cbfg.world-example0/world-vis-init})

(let [world (last (string/split (.-href (.-location js/window)) #"/"))
      maker (makers world)]
  (if maker
    (maker "vis")
    (println "don't know a maker for world:" world)))
