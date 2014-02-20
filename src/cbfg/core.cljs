(ns cbfg.core
  (:require [om.core :as om :include-macros true]
            [om.dom :as dom :include-macros true]))

(enable-console-print!)

(def app-state
  (atom {:text "Hello world!"}))

(defn app-ui [app-state owner]
  (reify
    om/IRender
    (render [_]
      (dom/div nil
               (dom/h1 nil (:text app-state))))))

(om/root app-state
         app-ui
         (. js/document (getElementById "app")))
