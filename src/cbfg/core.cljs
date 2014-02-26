(ns cbfg.core
  (:require cbfg.ddl
            [om.core :as om :include-macros true]
            [om.dom :as dom :include-macros true]))

(enable-console-print!)

(print cbfg.ddl/hi)

;; ------------------------------------------------

(def bucket-kinds
  {:memcached {:cap 'cp
               :allowed-ops [:get :set :del :arith :append]}
   :couchbase {:cap 'cp
               :allowed-ops [:get :set :del :arith :append]}
   :couchdb   {:cap 'ap
               :allowed-ops [:get :cas-set :cas-del]}})

;; cfg stuff is admininistrator chosen values.
;; cfg stuff has uuid'ed / version / parent.

(def cfg-model {:name "name your model cfg"
                :clusters {}})
(def cfg-cluster {:parent-model nil
                  :name "west"})
(def cfg-bucket {:parent-cluster nil
                 :name "default"
                 :kind 'couchbase
                 :num-partitions 0
                 :per-node-mem-quota 0
                 :indexes {}
                 :storage-name "default"
                 :replica-count 0
                 :replica-rules []}) ; TODO: user/group/auth?
(def cfg-index {:parent-bucket nil
                :name "byCity"
                :path "address.city"
                :aggregate nil
                :storage-name "default"}) ; TODO: view?
(def cfg-node {:parent-cluster nil
               :name "aaa"
               :port 0
               :container "" ; for zone/row/rack/shelf awareness.
               :usage []
               :weight 0.0})
(def map-partitions {})

(def node {:cfg-node {}
           :memory {}
           :processors 4
           :storages {}
           :buckets {}
           :stats {}})
(def node-storage {})

(def node-bucket {:cfg-bucket {}
                  :node-collections {}})
(def node-collection {:node-partitions {}})
(def node-partition {})

(def write-queue {})
(def read-queue {})

(def send-queue {})
(def recv-queue {})

;; ------------------------------------------------

(def sim {:clock 0
          :init {}
          :model {}
          :choices []
          :past []
          :upcoming {}})

;; ------------------------------------------------

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
