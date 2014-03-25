;; Browser and UI-related stuff goes in this file.
;; generic stuff should go elsewhere.

(ns cbfg.core
  (:require-macros [cljs.core.async.macros :refer [go go-loop]]
                   [cbfg.ago :refer [ago ago-loop achan-buf aput atake]])
  (:require [clojure.string :as string]
            [cljs.core.async :refer [<! >! put! chan timeout merge map< dropping-buffer]]
            [goog.dom :as gdom]
            [goog.events :as gevents]
            cbfg.ddl
            [cbfg.fence :refer [make-fenced-pump]]))

(enable-console-print!)

(println cbfg.ddl/hi)

;; ------------------------------------------------

(defn no-prefix [s] (string/join "-" (rest (string/split s #"-"))))

(defn get-el-value [elId] (.-value (gdom/getElement elId)))

(defn set-el-innerHTML [elId v] (set! (.-innerHTML (gdom/getElement elId)) v))

(defn listen [el type]
  (let [out (chan)]
    (gevents/listen el type #(put! out %))
    out))

(defn dissoc-in [m [k & ks :as keys]]
  (if ks
    (if-let [next-map (get m k)]
      (let [new-map (dissoc-in next-map ks)]
        (if (seq new-map)
          (assoc m k new-map)
          (dissoc m k)))
      m)
    (dissoc m k)))

;; ------------------------------------------------

(defn vis-add-ch [vis ch first-taker-actx]
  (-> vis
      (update-in [:chs ch] #(if % % {:id ((:gen-id vis))
                                     :msgs {}
                                     :first-taker-actx first-taker-actx}))
      (update-in [:chs ch :first-taker-actx] #(if % % first-taker-actx))))

(def vis-event-handlers
  {:ago
   {:start (fn [vis actx args]
             (let [[child-actx] args]
               (swap! vis #(-> %
                               (assoc-in [:actxs child-actx]
                                         {:children {} :wait-chs {} :closed false})
                               (assoc-in [:actxs actx :children child-actx] true)))
               [{:delta :actx-start :actx actx :child-actx child-actx :after true}]))
    :end (fn [vis actx args]
           (let [[child-actx result] args]
             (swap! vis #(-> %
                             (dissoc-in [:actxs child-actx])
                             (dissoc-in [:actxs actx :children child-actx])))
             [{:delta :actx-end :actx actx :child-actx child-actx}]))}
   :ago-loop
   {:loop-state (fn [vis actx args]
                  (swap! vis #(assoc-in % [:actxs actx :loop-state] (first args)))
                  nil)}
   :aclose
   {:before (fn [vis actx args]
              (let [[ch] args] nil))
    :after (fn [vis actx args]
             (let [[ch result] args] nil))}
   :atake
   {:before (fn [vis actx args]
              (let [[ch-name ch] args]
                (swap! vis #(-> %
                                (assoc-in [:actxs actx :wait-chs ch] [:take ch-name])
                                (vis-add-ch ch actx)))
                nil))
    :after (fn [vis actx args]
             (let [[ch-name ch msg] args]
               (swap! vis #(-> %
                               (dissoc-in [:actxs actx :wait-chs ch])
                               (dissoc-in [:chs ch :msgs msg])))
               (when (nil? msg) ; The ch is closed.
                 (swap! vis #(dissoc-in % [:chs ch])))
               [{:delta :take :msg msg :ch ch :actx actx :ch-name ch-name}]))}
   :aput
   {:before (fn [vis actx args]
              (let [[ch-name ch msg] args]
                (swap! vis #(-> %
                                (assoc-in [:actxs actx :wait-chs ch] [:put ch-name])
                                (vis-add-ch ch nil)
                                (assoc-in [:chs ch :msgs msg] true)))
                [{:delta :put :msg msg :actx actx :ch ch :ch-name ch-name}]))
    :after (fn [vis actx args]
             (let [[ch-name ch msg result] args]
               (swap! vis #(-> %
                               (dissoc-in [:actxs actx :wait-chs ch])))
               ; NOTE: Normally we should cleanup ch when nil result but
               ; looks like CLJS async always incorrectly returns nil from >!.
               nil))}
   :aalts
   {:before (fn [vis actx args]
              (let [[ch-bindings] args
                    ; The ch-actions will be [[ch :take] [ch :put] ...].
                    ch-actions (map #(if (seq? %) [(first %) :put (second %)] [% :take])
                                    ch-bindings)]
                (apply concat
                       (mapv (fn [ch-action]
                               (let [[ch action & msgv] ch-action]
                                 (swap! vis #(-> %
                                                 (vis-add-ch ch
                                                             (when (= action :take) actx))
                                                 (assoc-in [:actxs actx :wait-chs ch]
                                                           [action])))
                                 (when (= action :put)
                                   [{:delta :put :msg (first msgv) :actx actx :ch ch}])))
                             ch-actions))))
    :after (fn [vis actx args]
             (let [[ch-bindings result] args
                   chs (map #(if (seq? %) (first %) %) ch-bindings)
                   [result-msg result-ch] result]
               (doseq [ch chs]
                 (swap! vis #(dissoc-in % [:actxs actx :wait-chs ch])))
               (swap! vis #(dissoc-in % [:chs result-ch :msgs result-msg]))
               (when (nil? result-msg) ; The ch is closed.
                 (swap! vis #(-> %
                                 (dissoc-in [:actxs actx :wait-chs result-ch])
                                 (dissoc-in [:chs result-ch]))))
               (when (some #(= % result-ch) ch-bindings)
                 [{:delta :take :msg result-msg :ch result-ch :actx actx}])))}})

;; ------------------------------------------------

(defn assign-position [positions id override]
  (if override
    (swap! positions #(assoc-in % [id] override))
    (swap! positions #(-> %
                          (assoc-in [id] (get % 'NEXT-POSITION))
                          (update-in ['NEXT-POSITION] (fnil inc 0))))))

(defn assign-positions [vis actx positions actx-ch-ch-infos override]
  (let [actx-id (last actx)
        actx-info (get-in vis [:actxs actx])]
    (assign-position positions actx-id override)
    (doseq [[ch ch-info] (get actx-ch-ch-infos actx)]
      (assign-position positions (:id ch-info) override))
    (doseq [child-actx (sort #(compare (last %1) (last %2))
                             (keys (:children actx-info)))]
      (assign-positions vis child-actx positions actx-ch-ch-infos
                        (if override override
                            (when (:closed actx-info)
                              (get-in @positions [actx-id])))))))

;; ------------------------------------------------

(defn vis-html-ch [vis ch]
  (if ch
    ["(ch " (:id (get-in vis [:chs ch])) ")"]
    "nil"))

(defn vis-html-loop-state
  ([vis loop-state]
     (interpose ", " (map (fn [x] [(vis-html-loop-state vis (first x) (second x))])
                          loop-state)))
  ([vis name val]
     (let [name-parts (string/split (str name) #"-")]
       (cond (= "ch" (last name-parts)) [name " " (vis-html-ch vis val)]
             (= "chs" (last name-parts)) [name " ["
                                          (interpose ", "
                                                     (map #(vis-html-ch vis %) val))
                                          "]"]
             :default [name " " (if val val "nil")]))))

(defn vis-html-actx [vis actx actx-ch-ch-infos]
  (let [actx-id (last actx)
        actx-info (get-in vis [:actxs actx])
        children (:children actx-info)]
    ["<div id='actx-" actx-id "' class='actx'>"
     "<button class='toggle' id='toggle-" actx-id "'>"
     (if (:closed actx-info) "&#9654;" "&#9660;")
     "</button>"
     actx-id
     "<div class='loop-state'>" (vis-html-loop-state vis (:loop-state actx-info)) "</div>"
     "<div class='chs'><ul>"
     (mapv (fn [ch-ch-info]
             (let [ch-info (second ch-ch-info)
                   ch-id (:id ch-info)]
               ["<li id='ch-" ch-id "'>" ch-id ": " (:msgs ch-info) "</li>"]))
           (get actx-ch-ch-infos actx))
     "</ul></div>"
     (when (not (:closed actx-info))
       ["<ul>" (mapv (fn [child-actx]
                       ["<li>" (vis-html-actx vis child-actx actx-ch-ch-infos)
                        "</li>"])
                     (sort #(compare (last %1) (last %2)) (keys children)))
        "</ul>"])
     "</div>"]))

;; ------------------------------------------------

(defn vis-svg-arrow [class x1 y1 x2 y2]
  ["<line class='arrow " class "' x1='" x1 "' y1='" y1 "' x2='" x2 "' y2='" y2 "'/>"])

(defn vis-svg-actxs [vis positions deltas after]
  (let [stroke-width 1
        line-height 21
        chs (:chs vis)
        ch-y (fn [ch] (* line-height (+ 0.5 (get positions (:id (get chs ch))))))
        actx-y (fn [actx] (* line-height (+ 0.5 (get positions (last actx)))))]
    ["<defs>"
     "<marker id='triangle' viewBox='0 0 10 10' refX='0' refY='5'  orient='auto'"
     " markerUnits='strokeWidth' markerWidth='8' markerHeight='6'>"
     " <path d='M 0 0 L 10 5 L 0 10 z'/></marker>"
     "</defs>"
     (mapv (fn [actx-actx-info]
             (let [[actx actx-info] actx-actx-info]
               (mapv (fn [ch-action]
                       (let [[ch wait-kind-ch-name] ch-action
                             [wait-kind ch-name] wait-kind-ch-name
                             ay (actx-y actx)
                             cy (ch-y ch)]
                         (when (not= ay cy)
                           [(if (= :put wait-kind)
                              (vis-svg-arrow "" 500 ay 600 cy)
                              (vis-svg-arrow "" 600 cy 500 ay))
                            (when ch-name
                              ["<text class='ch-name' x='540' y='" (* 0.5 (+ ay cy))
                               "'>" ch-name "</text>"])])))
                     (:wait-chs actx-info))))
           (:actxs vis))
     (mapv (fn [delta]
             (when (= after (not (not (:after delta))))
               (let [childy (actx-y (:child-actx delta))
                     ay (actx-y (:actx delta))
                     cy (ch-y (:ch delta))]
                 (when (not= ay cy)
                   [(case (:delta delta)
                      :put (when (get chs (:ch delta))
                             ["<g transform='translate(500," ay ")'>"
                              (vis-svg-arrow "delta" 0 0 100 (- cy ay)) "</g>"])
                      :take (when (get chs (:ch delta))
                              ["<g transform='translate(600," cy ")'>"
                               (vis-svg-arrow (if (:msg delta) "delta" "delta close")
                                              0 0 -100 (- ay cy)) "</g>"])
                      :actx-start (when (> childy line-height)
                                    ["<g transform='translate(30," ay ")'>"
                                     (vis-svg-arrow "delta" 0 0 30 (- childy ay)) "</g>"])
                      :actx-end (when (> childy line-height)
                                  ["<g transform='translate(60," childy ")'>"
                                   (vis-svg-arrow "delta close" 0 0 -40 0) "</g>"])
                      nil)
                    (when (:ch-name delta)
                      ["<text class='ch-name' x='540' y='" (* 0.5 (+ ay cy))
                       "'>" (:ch-name delta) "</text>"])]))))
           deltas)]))

;; ------------------------------------------------

(defn vis-run-controls [event-delay step-ch el-prefix]
  (let [run-controls {"run"      #(do (when (< @event-delay 0) (put! step-ch true))
                                      (reset! event-delay 0))
                      "run-slow" #(do (when (< @event-delay 0) (put! step-ch true))
                                      (reset! event-delay
                                              (js/parseInt (get-el-value (str el-prefix "-run-slowness")))))
                      "pause"    #(reset! event-delay -1)
                      "step"     #(do (reset! event-delay -1)
                                      (put! step-ch true))}]
    (go-loop [run-control-ch (merge (map #(listen (gdom/getElement (str el-prefix "-" %)) "click")
                                         (keys run-controls)))]
      ((get run-controls (no-prefix (.-id (.-target (<! run-control-ch))))))
      (recur run-control-ch))))

;; ------------------------------------------------

(defn vis-init [world-init-cb el-prefix]
  (let [event-delay (atom 0)
        event-ch (chan)
        step-ch (chan (dropping-buffer 1))
        last-id (atom 0)
        gen-id #(swap! last-id inc)
        w [{:gen-id gen-id
            :event-ch event-ch}]
        vis (atom {:actxs {} ; {actx -> {:children {child-actx -> true},
                             ;           :wait-chs {ch -> [:take|:put optional-ch-name])},
                             ;           :loop-state last-loop-bindings
                             ;           :closed bool}}.
                   :chs {}   ; {ch -> {:id (gen-id),
                             ;         :msgs {msg -> true}
                             ;         :first-taker-actx actx-or-nil}}.
                   :gen-id gen-id})
        render-ch (chan)]
    (go-loop [num-events 0]  ; Process events from world / simulation.
      (let [[actx event] (<! event-ch)
            [verb step & args] event
            deltas ((get (get vis-event-handlers verb) step) vis actx args)
            event-str (str num-events ": " (last actx) " " verb " " step " " args)]
        (when (and (not (zero? @event-delay)) (some #(not (:after %)) deltas))
          (>! render-ch [@vis deltas false event-str])
          (when (> @event-delay 0) (<! (timeout @event-delay)))
          (when (< @event-delay 0) (<! step-ch)))
        (>! render-ch [@vis deltas true event-str])
        (when (> @event-delay 0) (<! (timeout @event-delay)))
        (when (< @event-delay 0) (<! step-ch)))
        (recur (inc num-events)))
    (ago world w
         (go-loop [vis-last nil ; Process render-ch, updating U/I.
                   vis-last-positions nil
                   vis-last-html nil
                   vis-last-svg nil]
           (let [[vis-next deltas after event-str] (<! render-ch)]
             (set-el-innerHTML (str el-prefix "-event") event-str)
             (if after
               (let [vis-next-positions (atom {})
                     actx-ch-ch-infos (group-by #(:first-taker-actx (second %)) (:chs vis-next))]
                 (assign-positions vis-next world vis-next-positions actx-ch-ch-infos nil)
                 (let [vis-next-html (apply str (flatten (vis-html-actx vis-next world
                                                                        actx-ch-ch-infos)))
                       vis-next-svg (apply str (flatten (vis-svg-actxs vis-next @vis-next-positions
                                                                       deltas true)))]
                   (when (not= vis-next-html vis-last-html)
                     (set-el-innerHTML (str el-prefix "-html") vis-next-html))
                   (when (not= vis-next-svg vis-last-svg)
                     (set-el-innerHTML (str el-prefix "-svg") vis-next-svg))
                   (recur vis-next @vis-next-positions vis-next-html vis-next-svg)))
               (let [vis-next-svg (apply str (flatten (vis-svg-actxs vis-last vis-last-positions
                                                                     deltas false)))]
                 (when (not= vis-next-svg vis-last-svg)
                   (set-el-innerHTML (str el-prefix "-svg") vis-next-svg))
                 (recur vis-last vis-last-positions vis-last-html vis-next-svg)))))
         (world-init-cb world))
    (let [toggle-ch (listen (gdom/getElement (str el-prefix "-html")) "click")]
      (go-loop []
        (let [actx-id (no-prefix (.-id (.-target (<! toggle-ch))))]
          (doseq [[actx actx-info] (:actxs @vis)]
            (when (= actx-id (last actx))
              (swap! vis #(assoc-in % [:actxs actx :closed] (not (:closed actx-info))))
              (>! render-ch [@vis nil true nil])))
          (recur))))
    (vis-run-controls event-delay step-ch el-prefix)))

;; ------------------------------------------------

(defn example-add [actx x y delay]
  (ago example-add actx
       (let [timeout-ch (timeout delay)]
         (atake example-add timeout-ch)
         (+ x y))))

(defn example-sub [actx x y delay]
  (ago example-sub actx
       (let [timeout-ch (timeout delay)]
         (atake example-sub timeout-ch)
         (- x y))))

(def example-cmd-handlers
  {"add"  (fn [c] {:fence (:fence c) :rq #(example-add % (:x c) (:y c) (:delay c))})
   "sub"  (fn [c] {:fence (:fence c) :rq #(example-sub % (:x c) (:y c) (:delay c))})
   "test" (fn [c] {:fence (:fence c) :rq #(cbfg.fence/test %)})})

(def example-max-inflight (atom 10))

(defn example-init [el-prefix]
  (let [cmd-ch (map< (fn [ev] {:op (.-id (.-target ev))
                               :x (js/parseInt (get-el-value "x"))
                               :y (js/parseInt (get-el-value "y"))
                               :delay (js/parseInt (get-el-value "delay"))
                               :fence (= (get-el-value "fence") "1")})
                     (merge (map #(listen (gdom/getElement %) "click")
                                 (keys example-cmd-handlers))))]
    (vis-init (fn [world]
                (let [in-ch (achan-buf world 100)
                      out-ch (achan-buf world 0)]
                  (ago-loop user-in world [num-ins 0]
                            (let [cmd (<! cmd-ch)
                                  cmd-handler ((get example-cmd-handlers (:op cmd)) cmd)]
                              (aput user-in in-ch cmd-handler)
                              (recur (inc num-ins))))
                  (ago-loop user-out world [num-outs 0]
                            (let [result (atake user-out out-ch)]
                              (set-el-innerHTML (str el-prefix "-output") result)
                              (recur (inc num-outs))))
                  (make-fenced-pump world in-ch out-ch @example-max-inflight)))
              el-prefix)))

(example-init "vis")

