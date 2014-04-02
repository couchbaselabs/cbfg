;; Initializes the requested world / simulation.

(ns cbfg.core
  (:require [clojure.string :as string]
            cbfg.world-fence
            cbfg.world-store
            cbfg.world-grouper))

(enable-console-print!)

(def makers {"world-fence.html" cbfg.world-fence/world-vis-init
             "world-store.html" cbfg.world-store/world-vis-init})

(let [world (last (string/split (.-href (.-location js/window)) #"/"))
      maker (makers world)]
  (if maker
    (maker "vis")
    (println "don't know a maker for world:" world)))
