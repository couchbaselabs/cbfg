;; Browser and UI-related stuff goes in this file.
;; generic stuff should go elsewhere.

(ns cbfg.core
  (:require-macros [cljs.core.async.macros :refer [go go-loop]]
                   [cbfg.ago :refer [ago ago-loop achan-buf aput atake]])
  (:require [clojure.string :as string]
            [cljs.core.async :refer [<! >! put! chan timeout merge map<]]
            [goog.dom :as gdom]
            [goog.events :as gevents]
            cbfg.ddl
            [cbfg.fence :refer [make-fenced-pump]]))

(enable-console-print!)

(println cbfg.ddl/hi)

;; ------------------------------------------------

(defn get-el-value [elId]
  (.-value (gdom/getElement elId)))

(defn set-el-innerHTML [elId v]
  (set! (.-innerHTML (gdom/getElement elId)) v))

(defn listen [el type]
  (let [out (chan)]
    (gevents/listen el type (fn [e] (put! out e)))
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
      (update-in [:chs ch] #(if (nil? %)
                              {:id ((:gen-id vis))
                               :msgs {}
                               :first-taker-actx first-taker-actx}
                              %))
      (update-in [:chs ch :first-taker-actx] #(if % % first-taker-actx))))

(def vis-event-handlers
  {:ago
   {:start (fn [vis actx args]
             (let [[child-actx] args]
               (swap! vis #(-> %
                               (assoc-in [:actxs child-actx]
                                         {:children {} :wait-chs {}})
                               (assoc-in [:actxs actx :children child-actx] true)))
               [{:delta :actx-start :actx actx :child-actx child-actx :phase :curr}]))
    :end (fn [vis actx args]
           (let [[child-actx result] args]
             (swap! vis #(-> %
                             (dissoc-in [:actxs child-actx])
                             (dissoc-in [:actxs actx :children child-actx])))
             [{:delta :actx-end :actx actx :child-actx child-actx :phase :prev}]))}
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
               [{:delta :take :msg msg :ch ch :actx actx :phase :prev :ch-name ch-name}]))}
   :aput
   {:before (fn [vis actx args]
              (let [[ch-name ch msg] args]
                (swap! vis #(-> %
                                (assoc-in [:actxs actx :wait-chs ch] [:put ch-name])
                                (vis-add-ch ch nil)
                                (assoc-in [:chs ch :msgs msg] true)))
                [{:delta :put :msg msg :actx actx :ch ch :phase :prev :ch-name ch-name}]))
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
                                   [{:delta :put :msg (first msgv) :actx actx :ch ch
                                     :phase :prev}])))
                             ch-actions))))
    :after (fn [vis actx args]
             (let [[ch-bindings result] args
                   chs (map #(if (seq? %) (first %) %) ch-bindings)
                   [result-msg result-ch] result]
               (doseq [ch chs]
                 (swap! vis #(dissoc-in % [:actxs actx :wait-chs ch])))
               (swap! vis #(dissoc-in % [:chs result-ch :msgs result-msg]))
               (when (nil? result-msg) ; The ch is closed.
                 (swap! vis #(dissoc-in % [:chs result-ch])))
               (when (some #(= % result-ch) ch-bindings)
                 [{:delta :take :msg result-msg :ch result-ch :actx actx
                   :phase :prev}])))}})

;; ------------------------------------------------

(defn assign-position [positions id]
  ; NOTE: merge doesn't work as expected in CLJS.
  ; (swap! positions #(merge {id (count %)} %))
  ; (swap! positions (fn [x] (merge {id (count x)} x)))
  (swap! positions #(update-in % [id] (fn [v] (if v v (count %))))))

;; ------------------------------------------------

(defn vis-html-ch [vis ch]
  (if ch
    ["(ch " (:id (get-in vis [:chs ch])) ")"]
    "nil"))

(defn vis-html-loop-state
  ([vis loop-state]
     (interpose ", "
                (mapv (fn [nv] [(vis-html-loop-state vis
                                                     (first nv) (second nv))])
                      loop-state)))
  ([vis name val]
     (let [name-parts (string/split (str name) #"-")]
       (cond (= "ch" (last name-parts)) [name " " (vis-html-ch vis val)]
             (= "chs" (last name-parts)) [name " ["
                                          (interpose ", "
                                                     (map #(vis-html-ch vis %) val))
                                          "]"]
             :default [name " " (if val val "nil")]))))

;; ------------------------------------------------

(defn vis-html-actx [vis actx positions actx-ch-ch-infos]
  (let [actx-id (last actx)
        actx-info (get-in vis [:actxs actx])
        children (:children actx-info)]
    (assign-position positions actx-id)
    ["<div id='actx-" actx-id "' class='actx'>" actx-id
     "<div class='loop-state'>" (vis-html-loop-state vis (:loop-state actx-info)) "</div>"
     "<div class='chs'><ul>"
     (mapv (fn [ch-ch-info]
             (let [ch-info (second ch-ch-info)
                   ch-id (:id ch-info)]
               (assign-position positions ch-id)
               ["<li id='ch-" ch-id "'>" ch-id ": " (:msgs ch-info) "</li>"]))
           (get actx-ch-ch-infos actx))
     "</ul></div>"
     ["<ul>" (mapv (fn [child-actx]
                     ["<li>" (vis-html-actx vis child-actx positions actx-ch-ch-infos)
                      "</li>"])
                   (sort #(compare (last %1) (last %2)) (keys children)))
      "</ul>"]
     "</div>"]))

(defn vis-svg-actxs [vis positions deltas phase]
  (let [stroke-width 1
        line-height 21
        chs (:chs vis)
        ch-y (fn [ch] (* line-height (+ 0.5 (get positions (:id (get chs ch))))))
        actx-y (fn [actx] (* line-height (+ 0.5 (get positions (last actx)))))]
    ["<defs>"
     "<marker id='triangle' viewBox='0 0 10 10' refX='0' refY='5'  orient='auto'"
     " markerUnits='strokeWidth' markerWidth='8' markerHeight='6'>"
     " <path d='M 0 0 L 10 5 L 0 10 z'/>"
     "</defs>"
     (mapv (fn [actx-actx-info]
             (let [[actx actx-info] actx-actx-info]
               (mapv (fn [ch-action]
                       (let [[ch wait-kind-ch-name] ch-action
                             [wait-kind ch-name] wait-kind-ch-name
                             ay (actx-y actx)
                             cy (ch-y ch)]
                         [(if (= :put wait-kind)
                            ["<line x1='500' y1='" ay "' x2='600' y2='" cy
                             "' stroke='#faa' stroke-width='1' marker-end='url(#triangle)'/>"]
                            ["<line x1='600' y1='" cy "' x2='500' y2='" ay
                             "' stroke='#faa' stroke-width='1' marker-end='url(#triangle)'/>"])
                          (when ch-name
                            ["<text class='ch-name' x='540' y='" (* 0.5 (+ ay cy))
                             "'>" ch-name "</text>"])]))
                     (:wait-chs actx-info))))
           (:actxs vis))
     (mapv (fn [delta]
             (when (= phase (:phase delta))
               (let [childy (actx-y (:child-actx delta))
                     ay (actx-y (:actx delta))
                     cy (ch-y (:ch delta))]
                 [(case (:delta delta)
                    :put (when (get chs (:ch delta))
                           ["<g transform='translate(500," ay ")'>"
                            "<line class='delta' x1='0' y1='0' x2='100' y2='" (- cy ay)
                            "' stroke='green' stroke-width='1' marker-end='url(#triangle)'/></g>"])
                    :take (when (get chs (:ch delta))
                            ["<g transform='translate(600," cy ")'>"
                             "<line class='delta' x1='0' y1='0' x2='-100' y2='" (- ay cy)
                             "' stroke='" (if (:msg delta) "green" "black")
                             "' stroke-width='1' marker-end='url(#triangle)'/></g>"])
                    :actx-start (when (> childy line-height)
                                  ["<g transform='translate(30," ay ")'>"
                                   "<line class='delta' x1='0' y1='0' x2='30' y2='" (- childy ay)
                                   "' stroke='green' stroke-width='1' marker-end='url(#triangle)'/></g>"])
                    :actx-end (when (> childy line-height)
                                ["<g transform='translate(60," childy ")'>"
                                 "<line class='delta' x1='0' y1='0' x2='-30' y2='" (- ay childy)
                                 "' stroke='black' stroke-width='1' marker-end='url(#triangle)'/></g>"])
                    nil)
                  (when (:ch-name delta)
                    ["<text class='ch-name' x='540' y='" (* 0.5 (+ ay cy))
                     "'>" (:ch-name delta) "</text>"])])))
           deltas)]))

;; ------------------------------------------------

(defn vis-run-controls [event-delay step-ch]
  (let [run-controls {"run"      #(do (when (< @event-delay 0) (put! step-ch true))
                                      (reset! event-delay 0))
                      "run-slow" #(do (when (< @event-delay 0) (put! step-ch true))
                                      (reset! event-delay
                                              (js/parseInt (get-el-value "run-slowness"))))
                      "pause"    #(reset! event-delay -1)
                      "step"     #(do (reset! event-delay -1)
                                      (put! step-ch true))}]
    (go-loop [run-control-ch (merge (map #(listen (gdom/getElement %) "click")
                                         (keys run-controls)))]
      ((get run-controls (.-id (.-target (<! run-control-ch)))))
      (recur run-control-ch))))

(defn vis-init [cmds cmd-handlers]
  (let [max-inflight (atom 10)
        event-delay (atom 0)
        event-ch (chan)
        step-ch (chan)
        last-id (atom 0)
        gen-id #(swap! last-id inc)
        w [{:gen-id gen-id
            :event-ch event-ch}]
        root-actx (atom nil)
        vis (atom {:actxs {} ; {actx -> {:children {child-actx -> true},
                             ;           :wait-chs {ch -> [:take|:put optional-ch-name])},
                             ;           :loop-state last-loop-bindings}}.
                   :chs {}   ; {ch -> {:id (gen-id),
                             ;         :msgs {msg -> true}
                             ;         :first-taker-actx actx-or-nil}}.
                   :gen-id gen-id
                   :last-id (fn [] @last-id)})]
    (go-loop [num-events 0 vis-last nil vis-last-positions nil]
      (when (> @event-delay 0) (<! (timeout @event-delay)))
      (when (< @event-delay 0) (<! step-ch))
      (let [[actx event] (<! event-ch)
            [verb step & args] event
            vis-positions (atom {})
            vis-event-handler (get (get vis-event-handlers verb) step)
            deltas (vis-event-handler vis actx args)
            actx-ch-ch-infos (group-by #(:first-taker-actx (second %)) (:chs @vis))]
        (set-el-innerHTML "event"
                          (str num-events ": " (last actx) " " verb " " step " " args))
        (when (and (not (zero? @event-delay)) (not-empty deltas) vis-last vis-last-positions)
          (set-el-innerHTML "vis-svg"
                            (apply str (flatten (vis-svg-actxs vis-last vis-last-positions
                                                               deltas :prev))))
          (when (> @event-delay 0) (<! (timeout @event-delay)))
          (when (< @event-delay 0) (<! step-ch)))
        (set-el-innerHTML "vis-html"
                          (apply str (flatten (vis-html-actx @vis @root-actx vis-positions
                                                             actx-ch-ch-infos))))
        (set-el-innerHTML "vis-svg"
                          (apply str (flatten (vis-svg-actxs @vis @vis-positions
                                                             deltas :curr))))
        (recur (inc num-events) @vis @vis-positions)))
    (ago world w
         (reset! root-actx world)
         (let [in (achan-buf world 100)
               out (achan-buf world 0)]
           (ago-loop user-in world [num-ins 0]
                     (let [cmd (<! cmds)
                           cmd-handler ((get cmd-handlers (:op cmd)) cmd)]
                       (aput user-in in cmd-handler)
                       (recur (inc num-ins))))
           (ago-loop user-out world [num-outs 0]
                     (let [result (atake user-out out)]
                       (set-el-innerHTML "output" result)
                       (recur (inc num-outs))))
           (make-fenced-pump world in out @max-inflight)))
    (vis-run-controls event-delay step-ch)))

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
  {"add"  (fn [cmd] {:rq #(example-add % (:x cmd) (:y cmd) (:delay cmd))
                     :fence (:fence cmd)})
   "sub"  (fn [cmd] {:rq #(example-sub % (:x cmd) (:y cmd) (:delay cmd))
                     :fence (:fence cmd)})
   "test" (fn [cmd] {:rq #(cbfg.fence/test %)
                     :fence (:fence cmd)})})

(vis-init (map< (fn [ev] {:op (.-id (.-target ev))
                          :x (js/parseInt (get-el-value "x"))
                          :y (js/parseInt (get-el-value "y"))
                          :delay (js/parseInt (get-el-value "delay"))
                          :fence (= (get-el-value "fence") "1")})
                (merge (map #(listen (gdom/getElement %) "click")
                            (keys example-cmd-handlers))))
          example-cmd-handlers)
