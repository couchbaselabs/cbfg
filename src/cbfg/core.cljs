(ns cbfg.core
  (:require [om.core :as om :include-macros true]
            [om.dom :as dom :include-macros true]))

(enable-console-print!)

(defn widget [data owner]
  (reify
    om/IRender
    (render [_]
      (dom/h1 nil (:text data)))))

(om/root {:text "Hello world!"}
         widget
         (. js/document (getElementById "app")))
