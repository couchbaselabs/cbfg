(ns cbfg.world.t1
  (:require-macros [cljs.core.async.macros :refer [go-loop]]
                   [cbfg.act :refer [act actx-top achan achan-buf aput atake]])
  (:require [cljs.core.async :refer [<! >! close! chan map< merge timeout dropping-buffer]]
            [goog.dom :as gdom]
            [om.core :as om :include-macros true]
            [om.dom :as dom :include-macros true]
            [ago.core :refer [make-ago-world ago-chan ago-timeout]]
            [cbfg.vis :refer [listen-el get-el-value get-el-innerHTML]]
            [cbfg.net :refer [make-net]]
            [cbfg.lane]
            [cbfg.world.net]
            [cbfg.world.lane]
            [cbfg.world.base :refer [world-cmd-loop]]))

;; TODO: How to assign locations to world entities before rendering?

(def prog-world (atom {})) ; { :world => world-actx
                           ;   :net-listen-ch => ch
                           ;   :net-connect-ch => ch
                           ;   :servers => { server-addr => ports }
                           ;   :clients => { client-addr => client-info }
                           ;   :res-ch => ch }

(def run-history
  (atom {:snapshots {0 {}
                     10 {:a 1 :b 2 :c 3 :d 4}
                     20 {:a 11 :b 22 :c 33 :d 44}}
         :events [[0 :snapshot]
                  [1 :event [:a 1]]
                  [2 :event [:b 2]]
                  [3 :event [:c 3]]
                  [4 :event [:d 4]]
                  [10 :snapshot]
                  [11 :event [:a 11]]
                  [12 :event [:b 22]]
                  [13 :event [:c 33]]
                  [14 :event [:d 44]]
                  [20 :snapshot]
                  [21 :event [:a 21]]
                  [22 :event [:b 22]]

                  [0 :snapshot]
                  [1 :event [:a 1]]
                  [2 :event [:b 2]]
                  [3 :event [:c 3]]
                  [4 :event [:d 4]]
                  [10 :snapshot]
                  [11 :event [:a 11]]
                  [12 :event [:b 22]]
                  [13 :event [:c 33]]
                  [14 :event [:d 44]]
                  [20 :snapshot]
                  [21 :event [:a 21]]
                  [22 :event [:b 22]]]}))

(def run-world       (atom {:a 10}))
(def run-world-hover (atom nil))

; -------------------------------------------------------------------

(defn render-world [app owner]
  (apply dom/ul nil
         (map (fn [[k v]] (dom/li nil (str k ":" v))) app)))

(defn render-snapshot [app owner ss-ts]
  (render-world (get-in app [:snapshots ss-ts] nil) owner))

(defn on-event-focus [snapshot-ts event-ts]
  (when-let [ss (get-in @run-history [:snapshots snapshot-ts])]
    (reset! run-world-hover ss)
    (.add gdom/classes (gdom/getElement "world-container") "hover")))

(defn on-event-blur [snapshot-ts event-ts]
  (reset! run-world-hover nil)
  (.remove gdom/classes (gdom/getElement "world-container") "hover"))

(defn render-events [app owner]
  (apply dom/ul nil
         (second
          (reduce
           (fn [[last-snapshot-ts res] [ts kind args]]
             (if (= kind :snapshot)
               [ts (conj res
                         (dom/li #js {:className "snapshot"
                                      :onMouseEnter #(on-event-focus ts nil)
                                      :onMouseLeave #(on-event-blur ts nil)}
                                 (str ts)))]
               [(or last-snapshot-ts ts)
                (conj res
                      (dom/li #js {:onMouseEnter
                                   #(on-event-focus last-snapshot-ts ts)
                                   :onMouseLeave
                                   #(on-event-blur last-snapshot-ts ts)}
                              (str ts (pr-str args))))]))
           [nil []]
           (:events app)))))

(defn render-clients [app owner]
  (apply dom/select #js {:id "client"}
         (map (fn [client-addr]
                (dom/option #js {:value client-addr} (str client-addr)))
              (keys (:clients app)))))

(defn init-roots []
  (om/root render-world run-world
           {:target (. js/document (getElementById "world"))})
  (om/root render-world run-world
           {:target (. js/document (getElementById "world-map"))})
  (om/root render-world run-world-hover
           {:target (. js/document (getElementById "world-hover"))})
  (om/root render-world run-world-hover
           {:target (. js/document (getElementById "world-map-hover"))})
  (om/root render-events run-history
           {:target (. js/document (getElementById "events"))})
  (om/root render-clients prog-world
           {:target (. js/document (getElementById "controls-clients"))}))

; ------------------------------------------------

(defn world-vis-init [el-prefix init-event-delay]
  (init-roots)
  (let [prog-ch (listen-el (gdom/getElement "prog-go") "click")
        step-ch (chan (dropping-buffer 1))
        event-delay (atom init-event-delay)
        run-controls-ch (cbfg.vis/vis-run-controls event-delay step-ch "header")]
    (go-loop [num-worlds 0]
      (let [last-id (atom 0)
            gen-id #(swap! last-id inc)
            agw (make-ago-world num-worlds)
            get-agw (fn [] agw)
            event-ch (ago-chan agw)
            make-timeout-ch (fn [actx delay] (ago-timeout agw delay))
            w [{:gen-id gen-id
                :get-agw get-agw
                :event-ch event-ch
                :make-timeout-ch make-timeout-ch}]
            world (conj w "world-0")  ; No act for world actx init to avoid recursion.
            vis (atom
                 {:actxs {world {:children {} ; child-actx -> true,
                                 :wait-chs {} ; ch -> [:ghost|:take|:put optional-ch-name],
                                 :collapsed true
                                 ; :loop-state last-loop-bindings,
                                 }}
                  :chs {} ; {ch -> {:id (gen-id), :msgs {msg -> true},
                          ;         :first-taker-actx actx-or-nil}}.
                  :gen-id gen-id})
            render-ch (chan)
            render-cb (fn [vis-next]
                        (println :on-render-cb @last-id))]
        (reset! prog-world {:world world
                            :net-listen-ch (achan-buf world 10)
                            :net-connect-ch (achan-buf world 10)
                            :servers {}
                            :clients {}
                            :res-ch (achan-buf world 10)})
        (cbfg.vis/process-events vis event-delay cbfg.vis/vis-event-handlers
                                 event-ch step-ch render-ch)
        (cbfg.vis/process-render el-prefix world render-ch render-cb)
        (make-net world
                  (:net-listen-ch @prog-world)
                  (:net-connect-ch @prog-world))
        (let [req-ch (achan world)
              res-ch (achan world)
              vis-chs {}
              cmd-ch (map< (fn [ev] {:op (.-id (.-target ev))
                                     :x (js/parseInt (get-el-value "x"))
                                     :y (js/parseInt (get-el-value "y"))
                                     :delay (js/parseInt (get-el-value "delay"))
                                     :fence (= (get-el-value "fence") "1")
                                     :lane (get-el-value "lane")
                                     :client (get-el-value "client")
                                     :color (get-el-value "color")
                                     :sleep (js/parseInt (get-el-value "sleep"))})
                           (merge (map #(listen-el (gdom/getElement %) "click")
                                       (keys cbfg.world.lane/cmd-handlers))))
              prog (get-el-value "prog")
              prog-js (str "with (cbfg.world.t1) {" prog "}")
              prog-res (try (js/eval prog-js) (catch js/Object ex ex))]
          (world-cmd-loop world cbfg.world.lane/cmd-handlers cmd-ch
                          req-ch res-ch vis-chs world-vis-init el-prefix)
          (println :prog-res prog-res)
          (<! prog-ch)
          (close! cmd-ch)
          (close! event-ch)
          (close! render-ch)
          (recur (inc num-worlds)))))))

; --------------------------------------------

(defn wait-done [done]
  (loop []
    (when (not @done)
      (cljs.core.async.impl.dispatch/process-messages)
      (recur))))

; --------------------------------------------

(defn kv-server [server-addr & ports]
  (let [world (:world @prog-world)
        done (atom false)]
    (act server-init world
         (doseq [port ports]
           (when-let [listen-result-ch (achan server-init)]
             (aput server-init (:net-listen-ch @prog-world)
                   [server-addr port listen-result-ch])
             (when-let [[accept-ch close-accept-ch] (atake server-init listen-result-ch)]
               (cbfg.world.net/server-accept-loop world accept-ch close-accept-ch)
               (swap! prog-world #(update-in % [:servers server-addr] conj port)))))
         (reset! done true))
    (wait-done done)))

(defn kv-client [client-addr server-addr server-port]
  (let [world (:world @prog-world)
        done (atom false)]
    (act client-init world
         (let [req-ch (cbfg.world.net/client-loop world (:net-connect-ch @prog-world)
                                                  server-addr server-port
                                                  client-addr (:res-ch @prog-world))]
           (swap! prog-world #(assoc-in % [:clients client-addr]
                                        {:client-addr client-addr
                                         :server-addr server-addr
                                         :server-port server-port
                                         :req-ch req-ch}))
           (reset! done true)))
    (wait-done done)))
