(ns cbfg.core
  (:require [om.core :as om :include-macros true]
            [om.dom :as dom :include-macros true]))

(enable-console-print!)

(def model-multi-cluster {})
(def model-cluster {})
(def model-bucket {})
(def model-index {})

(def model-partition {})

(def model-node {})

(def model-storage {})
(def model-write-queue {})
(def model-read-queue {})

(def model-socket {})
(def model-send-queue {})
(def model-recv-queue {})

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
