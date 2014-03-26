;; Initializes the requested world / simulation.

(ns cbfg.core
  (:require [clojure.string :as string]
            cbfg.world-example0))

(enable-console-print!)

(def makers {"world-example0.html" cbfg.world-example0/world-vis-init})

(let [world (last (string/split (.-href (.-location js/window)) #"/"))
      maker (makers world)]
  (if maker
    (maker "vis")
    (println "don't know a maker for world:" world)))
