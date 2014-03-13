;; browser and UI-related stuff goes in this file.
;; generic stuff should go elsewhere.

(ns cbfg.core
  (:require-macros [cljs.core.async.macros :refer [go]])
  (:require cbfg.ddl
            cbfg.fence
            [goog.dom :as dom]
            [goog.events :as events]
            [cljs.core.async :refer [<! put! chan]]))

(enable-console-print!)

(println cbfg.ddl/hi)

(defn listen [el type]
  (let [out (chan)]
    (events/listen el type (fn [e] (put! out e)))
    out))

(let [clicks (listen (dom/getElement "go") "click")]
  (go (while true
        (<! clicks)
        (set! (.-innerHTML (dom/getElement "output"))
              (user-input)))))

(defn user-input []
  (.-value (dom/getElement "input")))

;; ------------------------------------------------

(def sim {:clock 0
          :init {}
          :model {}
          :choices []
          :past []
          :upcoming {}})

