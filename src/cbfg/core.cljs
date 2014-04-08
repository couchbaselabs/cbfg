;; Initializes the requested world / simulation.

(ns cbfg.core
  (:require [clojure.string :as string]
            cbfg.world-fence
            cbfg.world-lane
            cbfg.world-grouper
            cbfg.world-net
            cbfg.world-npr
            cbfg.world-store))

(enable-console-print!)

(def makers {"world-fence.html" cbfg.world-fence/world-vis-init
             "world-lane.html"  cbfg.world-lane/world-vis-init
             "world-net.html"   cbfg.world-net/world-vis-init
             "world-store.html" cbfg.world-store/world-vis-init})

(let [world (last (string/split (.-href (.-location js/window)) #"/"))
      maker (makers world 0)]
  (if maker
    (maker "vis")
    (println "don't know a maker for world:" world)))
