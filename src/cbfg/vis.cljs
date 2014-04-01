;; Browser and UI-related stuff goes in this file.

(ns cbfg.vis
  (:require-macros [cljs.core.async.macros :refer [go-loop]]
                   [cbfg.ago :refer [ago]])
  (:require [clojure.string :as string]
            [cljs.core.async :refer [<! >! put! chan timeout merge dropping-buffer]]
            [goog.dom :as gdom]
            [goog.events :as gevents]
            [cbfg.misc :refer [dissoc-in]]))

(defn no-prefix [s] (string/join "-" (rest (string/split s #"-"))))

(defn get-el-value [elId] (.-value (gdom/getElement elId)))
(defn get-el-innerHTML [elId] (.-innerHTML (gdom/getElement elId)))
(defn set-el-innerHTML [elId v] (set! (.-innerHTML (gdom/getElement elId)) v))

(defn listen-el [el type]
  (let [out (chan)]
    (gevents/listen el type #(put! out %))
    out))

(defn log-el [el-prefix log-kind msg]
  (set-el-innerHTML (str el-prefix "-" log-kind "-log")
                    (str msg "\n"
                         (get-el-innerHTML (str el-prefix "-" log-kind "-log")))))

;; ------------------------------------------------

(defn vis-add-ch [vis ch first-taker-actx]
  (-> vis
      (update-in [:chs ch] #(if % % {:id ((:gen-id vis))
                                     :msgs {}
                                     :first-taker-actx first-taker-actx}))
      (update-in [:chs ch :first-taker-actx] #(if % % first-taker-actx))))

(def vis-event-handlers
  {:ago
   {:start (fn [vis actx [child-actx]]
             (swap! vis #(-> %
                             (assoc-in [:actxs child-actx]
                                       {:children {} :wait-chs {} :closed false})
                             (assoc-in [:actxs actx :children child-actx] true)))
             [{:delta :actx-start :actx actx :child-actx child-actx :after true}])
    :end (fn [vis actx [child-actx result]]
           (swap! vis #(-> %
                           (dissoc-in [:actxs child-actx])
                           (dissoc-in [:actxs actx :children child-actx])))
           [{:delta :actx-end :actx actx :child-actx child-actx}])}
   :ago-loop
   {:loop-state (fn [vis actx [loop-state]]
                  (swap! vis #(assoc-in % [:actxs actx :loop-state] loop-state))
                  nil)}
   :aclose
   {:before (fn [vis actx [ch]] nil)
    :after (fn [vis actx [ch result]] nil)}
   :atake
   {:before (fn [vis actx [ch-name ch]]
              (swap! vis #(-> %
                              (assoc-in [:actxs actx :wait-chs ch] [:take ch-name])
                              (vis-add-ch ch actx)))
              nil)
    :after (fn [vis actx [ch-name ch msg]]
             (swap! vis #(-> %
                             (dissoc-in [:actxs actx :wait-chs ch])
                             (dissoc-in [:chs ch :msgs msg])))
             (when (nil? msg) ; The ch is closed.
               (swap! vis #(dissoc-in % [:chs ch])))
             [{:delta :take :msg msg :ch ch :actx actx :ch-name ch-name}])}
   :aput
   {:before (fn [vis actx [ch-name ch msg]]
              (swap! vis #(-> %
                              (assoc-in [:actxs actx :wait-chs ch] [:put ch-name])
                              (vis-add-ch ch nil)
                              (assoc-in [:chs ch :msgs msg] true)))
              [{:delta :put :msg msg :actx actx :ch ch :ch-name ch-name}])
    :after (fn [vis actx [ch-name ch msg result]]
             (swap! vis #(-> %
                             (dissoc-in [:actxs actx :wait-chs ch])))
               ; NOTE: Normally we should cleanup ch when nil result but
               ; looks like CLJS async always incorrectly returns nil from >!.
               nil)}
   :aalts
   {:before (fn [vis actx [ch-bindings]]
              (let [; The ch-actions will be [[ch :take] [ch :put] ...].
                    ch-actions (map #(if (seq? %) [(first %) :put (second %)] [% :take])
                                    ch-bindings)]
                (apply concat
                       (mapv (fn [[ch action & msgv]]
                               (swap! vis #(-> %
                                               (vis-add-ch ch
                                                           (when (= action :take) actx))
                                               (assoc-in [:actxs actx :wait-chs ch]
                                                         [action])))
                               (when (= action :put)
                                 [{:delta :put :msg (first msgv) :actx actx :ch ch}]))
                             ch-actions))))
    :after (fn [vis actx [ch-bindings [result-msg result-ch]]]
             (let [; The ch-actions will be [[ch :take] [ch :put] ...].
                   ch-actions (map #(if (seq? %) [(first %) :put (second %)] [% :take])
                                   ch-bindings)]
               (doseq [[ch action] ch-actions]
                 (swap! vis #(assoc-in % [:actxs actx :wait-chs ch] [:ghost])))
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
    (doseq [ch-info (sort-by :id (vals (get actx-ch-ch-infos actx)))]
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
     " <button class='toggle' id='toggle-" actx-id "'>"
     (if (:closed actx-info) "&#9654;" "&#9660;")
     " </button>"
     " <span class='actx-id'>" actx-id "</span>&nbsp;"
     " <div class='loop-state'>" (vis-html-loop-state vis (:loop-state actx-info)) "</div>"
     "<div class='chs'><ul>"
     (mapv (fn [ch-info]
             (let [ch-id (:id ch-info)]
               ["<li id='ch-" ch-id "'><span class='ch-id'>" ch-id ":</span>&nbsp;"
                "<span class='msgs'>" (keys (:msgs ch-info)) "</span></li>"]))
           (sort-by :id (vals (get actx-ch-ch-infos actx))))
     "</ul></div>"
     (when (not (:closed actx-info))
       ["<ul class='children'>"
        (mapv (fn [child-actx]
                ["<li>" (vis-html-actx vis child-actx actx-ch-ch-infos) "</li>"])
              (sort #(compare (last %1) (last %2)) (keys children)))
        "</ul>"])
     "</div>"]))

;; ------------------------------------------------

(defn vis-svg-line [class x1 y1 x2 y2]
  ["<line class='" class "' x1='" x1 "' y1='" y1 "' x2='" x2 "' y2='" y2 "'/>"])

(defn vis-svg-actxs [vis positions deltas after prev-deltas]
  (let [stroke-width 1
        line-height 21
        chs (:chs vis)
        ch-y (fn [ch] (* line-height (+ 0.5 (get positions (:id (get chs ch))))))
        actx-y (fn [actx] (* line-height (+ 0.5 (get positions (last actx)))))]
    ["<defs>"
     "<marker id='triangle' viewBox='0 0 10 10' refX='0' refY='5' orient='auto'"
     " markerUnits='1' markerWidth='4' markerHeight='3'>"
     " <path d='M 0 0 L 10 5 L 0 10 z'/></marker>"
     "</defs>"
     (mapv (fn [[after deltas]] ; First paint old trails so they're underneath new lines.
             (mapv (fn [delta]
                     (when (= after (not (not (:after delta))))
                       (let [childy (actx-y (:child-actx delta))
                             ay (actx-y (:actx delta))
                             cy (ch-y (:ch delta))]
                         (when (not= ay cy)
                           [(case (:delta delta)
                              :put (when (get chs (:ch delta))
                                     ["<g transform='translate(10," (+ 2 ay) ")'>"
                                      (vis-svg-line "trail" 0 0 90 (- cy ay)) "</g>"])
                              :take (when (and (get chs (:ch delta)) (:msg delta))
                                      ["<g transform='translate(100," (+ 2 cy) ")'>"
                                       (vis-svg-line "trail" 0 0 -90 (- ay cy)) "</g>"])
                              :actx-start (when (and (> childy line-height) (not= childy ay))
                                            ["<g transform='translate(12," (+ 2 ay) ")'>"
                                             (vis-svg-line "trail" 0 0 0 (- childy ay)) "</g>"])
                              nil)]))))
                   deltas))
           prev-deltas)
     (mapv (fn [actx-actx-info]
             (let [[actx actx-info] actx-actx-info]
               (mapv (fn [ch-action]
                       (let [[ch wait-kind-ch-name] ch-action
                             [wait-kind ch-name] wait-kind-ch-name
                             ay (actx-y actx)
                             cy (ch-y ch)]
                         (when (not= ay cy)
                           [(case wait-kind
                              :ghost (vis-svg-line "ghost" 10 ay 100 cy)
                              :put   (vis-svg-line "arrow" 10 ay 100 cy)
                              :take  (vis-svg-line "arrow" 100 cy 10 ay))
                            (when ch-name
                              ["<text class='ch-name' x='50' y='" (* 0.5 (+ ay cy))
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
                             ["<g transform='translate(10," ay ")'>"
                              (vis-svg-line "arrow delta" 0 0 90 (- cy ay)) "</g>"])
                      :take (when (get chs (:ch delta))
                              ["<g transform='translate(100," cy ")'>"
                               (vis-svg-line (str "arrow delta"
                                                  (when (not (:msg delta)) " close"))
                                             0 0 -90 (- ay cy)) "</g>"])
                      :actx-start (when (and (> childy line-height) (not= childy ay))
                                    ["<g transform='translate(10," ay ")'>"
                                     (vis-svg-line "arrow delta" 0 0 0 (- childy ay)) "</g>"])
                      :actx-end (when (and (> childy line-height) (not= childy ay))
                                  ["<g transform='translate(100," childy ")'>"
                                   (vis-svg-line "arrow delta close" 0 0 -90 0) "</g>"])
                      nil)
                    (when (:ch-name delta)
                      ["<text class='ch-name' x='50' y='" (* 0.5 (+ ay cy))
                       "'>" (:ch-name delta) "</text>"])]))))
           deltas)]))

;; ------------------------------------------------

(defn vis-run-controls [event-delay step-ch el-prefix]
  (let [run-controls {"run"   #(do (when (< @event-delay 0) (put! step-ch true))
                                   (reset! event-delay
                                           (js/parseInt (get-el-value (str el-prefix "-run-delay")))))
                      "pause" #(reset! event-delay -1)
                      "step"  #(do (reset! event-delay -1)
                                   (put! step-ch true))}]
    (go-loop [run-control-ch (merge (map #(listen-el (gdom/getElement (str el-prefix "-" %)) "click")
                                         (keys run-controls)))]
      ((get run-controls (no-prefix (.-id (.-target (<! run-control-ch))))))
      (recur run-control-ch))))

;; ------------------------------------------------

(defn vis-init [world-init-cb el-prefix on-render-cb]
  (let [event-delay (atom 0)
        event-ch (chan 10) ; TODO: hacks around RACE during early initialization!!
        step-ch (chan (dropping-buffer 1))
        last-id (atom 0)
        gen-id #(swap! last-id inc)
        w [{:gen-id gen-id
            :event-ch event-ch}]
        vis (atom {:actxs {} ; {actx -> {:children {child-actx -> true},
                             ;           :wait-chs {ch -> [:ghost|take|:put optional-ch-name])},
                             ;           :loop-state last-loop-bindings
                             ;           :closed bool}}.
                   :chs {}   ; {ch -> {:id (gen-id),
                             ;         :msgs {msg -> true}
                             ;         :first-taker-actx actx-or-nil}}.
                   :gen-id gen-id})
        render-ch (chan)]
    (go-loop [num-events 0]  ; Process events from world / simulation.
      (let [[actx [verb step & args]] (<! event-ch)
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
    (let [el-event (str el-prefix "-event") ; Process U/I related events.
          el-html (str el-prefix "-html")
          el-svg (str el-prefix "-svg")]
      (ago world w
           (go-loop [vis-last nil ; Process render-ch, updating U/I.
                     vis-last-positions nil
                     vis-last-html nil
                     vis-last-svg nil
                     prev-deltas nil]
             (let [[vis-next deltas after event-str] (<! render-ch)
                   next-deltas (take 20 (conj prev-deltas [after deltas]))]
               (set-el-innerHTML el-event event-str)
               (if after
                 (let [vis-next-positions (atom {})
                       actx-ch-ch-infos (group-by #(:first-taker-actx (second %)) (:chs vis-next))]
                   (assign-positions vis-next world vis-next-positions actx-ch-ch-infos
                                     (when (get-in vis-next [:actxs world :closed]) 0))
                   (let [vis-next-html (apply str (flatten (vis-html-actx vis-next world
                                                                          actx-ch-ch-infos)))
                         vis-next-svg (apply str (flatten (vis-svg-actxs vis-next @vis-next-positions
                                                                         deltas true prev-deltas)))]
                     (when (not= vis-next-html vis-last-html)
                       (set-el-innerHTML el-html vis-next-html)
                       (when on-render-cb
                         (on-render-cb vis-next)))
                     (when (not= vis-next-svg vis-last-svg)
                       (set-el-innerHTML el-svg vis-next-svg))
                     (recur vis-next @vis-next-positions vis-next-html vis-next-svg next-deltas)))
                 (let [vis-next-svg (apply str (flatten (vis-svg-actxs vis-last vis-last-positions
                                                                       deltas false prev-deltas)))]
                   (when (not= vis-next-svg vis-last-svg)
                     (set-el-innerHTML el-svg vis-next-svg))
                   (recur vis-last vis-last-positions vis-last-html vis-next-svg next-deltas)))))
           (world-init-cb world))
      (let [toggle-ch (listen-el (gdom/getElement el-html) "click")] ; Process toggle U/I events.
        (go-loop []
          (let [actx-id (no-prefix (.-id (.-target (<! toggle-ch))))]
            (doseq [[actx actx-info] (:actxs @vis)]
              (when (= actx-id (last actx))
                (swap! vis #(assoc-in % [:actxs actx :closed] (not (:closed actx-info))))
                (>! render-ch [@vis nil true nil])))
            (recur)))))
    (vis-run-controls event-delay step-ch el-prefix)))

;; ------------------------------------------------

(defn filter-r [r] (if (map? r)
                     (-> r
                         (dissoc :opaque)
                         (dissoc :op)
                         (dissoc :fence))
                     r))

(defn render-client-cmds [client-cmds]
  (set-el-innerHTML "client"
                    (apply str
                           (flatten ["<table>"
                                     "<tr><th>id</th><th>fence</th><th>op</th>"
                                     "    <th>args</th><th>responses</th></tr>"
                                     (map (fn [[opaque [request responses]]]
                                            ["<tr class='"
                                             (when (some #(:result (second %)) responses)
                                               "complete") "'>"
                                             " <td>" opaque "</td>"
                                             " <td>" (:fence request) "</td>"
                                             " <td>" (:op request) "</td>"
                                             " <td>" (filter-r request) "</td>"
                                             " <td class='responses'><ul>"
                                             (map (fn [[out-time response]]
                                                    ["<li style='margin-left: " out-time "em;'>"
                                                     (filter-r response)
                                                     "</li>"])
                                                  (reverse responses))
                                             "</ul></td>"
                                             "</tr>"])
                                          (sort #(compare (first %1) (first %2))
                                                client-cmds))
                                     "</table>"]))))
