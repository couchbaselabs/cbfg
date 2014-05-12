(ns cbfg.world.t1
  (:require-macros [cljs.core.async.macros :refer [go-loop]]
                   [cbfg.act :refer [act actx-top achan-buf]])
  (:require [cljs.core.async :refer [<! >! close! chan timeout dropping-buffer]]
            [goog.dom :as gdom]
            [om.core :as om :include-macros true]
            [om.dom :as dom :include-macros true]
            [ago.core :refer [make-ago-world ago-chan ago-timeout]]
            [cbfg.vis :refer [listen-el get-el-value get-el-innerHTML]]
            [cbfg.net :refer [make-net]]
            [cbfg.world.net]
            [cbfg.lane]))

;; TODO: How to assign locations to world entities before rendering?

(def run-controls
  (atom {:controls
         {"play" #(println :play)
          "step" #(println :step)
          "pause" #(println :pause)
          "spawn" #(let [w (.open js/window "")
                         h (get-el-innerHTML "main")
                         s (get-el-innerHTML "style")]
                     (.write (aget w "document")
                             (str "<html><body>"
                                  "  <div class='main'>" h "</div>"
                                  "  <style>" s "</style>"
                                  "</body></html>")))}
         :speed 1.0}))

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

(def run-world (atom {:a 10}))
(def run-world-hover (atom nil))

; -------------------------------------------------------------------

(def curr-world (atom nil))
(def curr-net (atom nil))

; -------------------------------------------------------------------

(defn render-controls[app owner]
     (apply dom/ul nil
            (map (fn [[k v]]
                   (dom/li nil (dom/button #js {:onClick v} k)))
                 (:controls app))))

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
               [ts
                (conj res
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

(defn init-roots []
  (om/root render-controls run-controls
           {:target (. js/document (getElementById "controls"))})
  (om/root render-world run-world
           {:target (. js/document (getElementById "world"))})
  (om/root render-world run-world
           {:target (. js/document (getElementById "world-map"))})
  (om/root render-world run-world-hover
           {:target (. js/document (getElementById "world-hover"))})
  (om/root render-world run-world-hover
           {:target (. js/document (getElementById "world-map-hover"))})
  (om/root render-events run-history
           {:target (. js/document (getElementById "events"))}))

; ------------------------------------------------

(def vis-event-handlers {})

(defn process-render [render-ch]
  (go-loop []
    (when-let [[vis-next deltas after event-str] (<! render-ch)]
      (println :render-ch event-str)
      (recur))))

(defn process-events [vis event-delay event-ch step-ch render-ch]
  (go-loop [num-events 0]
    (when-let [[actx [verb step & args]] (<! event-ch)]
      (println :event-ch (last actx) verb step)

      (when true
        (recur (inc num-events)))

      (let [deltas ((get (get vis-event-handlers verb) step) vis actx args)
            event-str (str num-events ": " (last actx) " " verb " " step " " args)]
        (when (and (not (zero? @event-delay)) (some #(not (:after %)) deltas))
          (>! render-ch [@vis deltas false event-str])
          (when (> @event-delay 0) (<! (timeout @event-delay)))
          (when (< @event-delay 0) (<! step-ch)))
        (>! render-ch [@vis deltas true event-str])
        (when (> @event-delay 0) (<! (timeout @event-delay)))
        (when (< @event-delay 0) (<! step-ch)))
      (recur (inc num-events)))))

(defn world-vis-init [el-prefix init-event-delay]
  (init-roots)
  (let [prog-ch (listen-el (gdom/getElement "prog-go") "click")
        step-ch (chan (dropping-buffer 1))
        render-ch (chan)]
    (process-render render-ch)
    (go-loop [num-worlds 0]
      (let [last-id (atom 0)
            gen-id #(swap! last-id inc)
            agw (make-ago-world num-worlds)
            get-agw (fn [] agw)
            event-ch (ago-chan agw)
            event-delay (atom init-event-delay)
            make-timeout-ch (fn [actx delay]
                              (ago-timeout ((:get-agw (actx-top actx))) delay))
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
                  :gen-id gen-id})]
        (reset! curr-world {:world world})
        (reset! curr-net {:net-listen-ch (achan-buf world 10)
                          :net-connect-ch (achan-buf world 10)})
        (process-events vis event-delay event-ch step-ch render-ch)
        (make-net world
                  (:net-listen-ch @curr-net)
                  (:net-connect-ch @curr-net))
        (let [prog (get-el-value "prog")
              prog-js (str "with (cbfg.world.t1) {" prog "}")
              prog-res (try (js/eval prog-js) (catch js/Object ex ex))]
          (println :prog-res prog-res)
          (<! prog-ch)
          (close! event-ch)
          (recur (inc num-worlds)))))))

; --------------------------------------------

(defn kv-server [name & ports]
  (doseq [port ports]
    (println :kv-server name port)))

(defn kv-client [name & ports]
  (println :kv-client name ports))
