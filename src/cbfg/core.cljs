;; Initializes the requested world / simulation.

(ns cbfg.core
  (:require-macros [cbfg.ago :refer [ago]])
  (:require [clojure.string :as string]
            [cljs.core.async :refer [chan <! merge map< sliding-buffer]]
            cbfg.world-fence
            cbfg.world-store
            cbfg.grouper-test))

(enable-console-print!)

(def makers {"world-fence.html" cbfg.world-fence/world-vis-init
             "world-store.html" cbfg.world-store/world-vis-init})

(let [world (last (string/split (.-href (.-location js/window)) #"/"))
      maker (makers world)]
  (if maker
    (maker "vis")
    (println "don't know a maker for world:" world)))

(let [last-id (atom 0)
      gen-id #(swap! last-id inc)]
  (ago grouper-test-actx [{:gen-id gen-id :event-ch (chan (sliding-buffer 1))}]
       (println "grouper-test:"
                (<! (cbfg.grouper-test/test grouper-test-actx 0)))))
