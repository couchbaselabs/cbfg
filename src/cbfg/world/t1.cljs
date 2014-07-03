(ns cbfg.world.t1
  (:require-macros [cljs.core.async.macros :refer [go-loop]]
                   [cbfg.act :refer [act act-loop actx-top achan achan-buf
                                     aalts aput atake]])
  (:require [cljs.core.async :refer [<! >! close! chan map< merge timeout
                                     dropping-buffer]]
            [goog.dom :as gdom]
            [om.core :as om :include-macros true]
            [om.dom :as dom :include-macros true]
            [ago.core :refer [make-ago-world ago-chan ago-timeout
                              ago-snapshot ago-restore]]
            [cbfg.vis :refer [listen-el get-el-value
                              get-el-innerHTML set-el-innerHTML]]
            [cbfg.net]
            [cbfg.lane]
            [cbfg.world.net]
            [cbfg.world.lane]
            [cbfg.world.base :refer [world-cmd-loop]]))

(defn actx-agw [actx] ((:get-agw (first actx))))

(def prog-base (atom {}))  ; Stable parts of prog, even during time-travel.
(def prog-curr (atom {}))  ; The current prog-frame.
(def prog-evts (atom []))  ; Each evt is [ts label prog-frame has-snapshot].
(def prog-ss   (atom {}))  ; ts => agw-snapshot.

(defn prog-init [world]
  (reset! prog-base {:world world
                     :net-listen-ch (achan-buf world 10)
                     :net-connect-ch (achan-buf world 10)
                     :res-ch (achan-buf world 10)})
  (reset! prog-curr {:ts 0
                     :servers {}   ; { server-addr => ports }.
                     :clients {}   ; { client-addr => client-info }.
                     :reqs    {}   ; { opaque-ts => [req, [[reply-ts reply] ...] }.
                     :net     {}}) ; The :loop-state of the net-actx.
  (reset! prog-evts []))

; ------------------------------------------------

(defn render-net [target-el-id net prev-addrs]
  (let [[addrs h] (cbfg.world.net/render-net-html net prev-addrs)]
    (set-el-innerHTML target-el-id (apply str (flatten h)))
    addrs))

; -------------------------------------------------------------------

(defn filter-r [r] (if (map? r)
                     (-> r
                         (dissoc :opaque)
                         (dissoc :op)
                         (dissoc :fence))
                     r))

(defn render-reqs-html [reqs]
   (apply str
          (flatten ["<table class='hist'>"
                    "<tr><th>lane</th><th>request and responses</th></tr>"
                    (map (fn [[ts [req resps]]]
                           ["<tr class='" (when (some #(or (:result (second %))
                                                           (:status (second %)))
                                                      resps)
                                            "complete") "'>"
                            " <td>" (:lane req) "</td>"
                            " <td><ul>"
                            "  <li class='evt evt-" ts
                            "'     onmouseenter='return onHoverEvt(this);"
                            "'     style='padding-left:" ts "em;'>"
                            "   <span style='color:" (:color req) ";'>&#9679;</span>"
                            req
                            "   <button id='replay-" ts "'>"
                            "    &lt; replay requests to here</button>"
                            "  </li>"
                            (map (fn [[resp-ts resp]]
                                   ["<li class='evt evt-" resp-ts
                                    "'   onmouseenter='return onHoverEvt(this);"
                                    "'   style='padding-left:" resp-ts "em;'>"
                                    " <span style='color:" (:color resp) ";'>&#9679;</span>"
                                    (-> (filter-r resp)
                                        (dissoc :lane)
                                        (dissoc :delay)
                                        (dissoc :sleep))
                                    "</li>"])
                                 (reverse resps))
                            " </ul></td>"
                            "</tr>"])
                         (sort (fn [[ts0 r0] [ts1 r1]]
                                 (compare [(:lane (first r0)) ts0]
                                          [(:lane (first r1)) ts1]))
                               reqs))
                    "</table>"])))

; -------------------------------------------------------------------

(defn prog-evt [world label prog-frame-fn]
  (let [prog-prev @prog-curr
        prog-next (swap! prog-curr #(prog-frame-fn (update-in % [:ts] inc)))
        need-snapshot (<= (.-length cljs.core.async.impl.dispatch/tasks) 0)]
    (swap! prog-evts #(conj % [(:ts prog-next) label prog-next need-snapshot]))
    (when need-snapshot ; Only snapshot when quiescent.
      (swap! prog-ss #(assoc % (:ts prog-next) (ago-snapshot (actx-agw world)))))
    (when (not= (:reqs prog-prev) (:reqs prog-next))
      (set-el-innerHTML "reqs" (render-reqs-html (:reqs prog-next))))))

; -------------------------------------------------------------------

(defn on-prog-frame-focus [prog-frame]
  (render-net "net-hover" (:net prog-frame) {})
  (.add gdom/classes (gdom/getElement "net-container") "hover"))

(defn on-prog-frame-blur []
  (set-el-innerHTML "net-hover" "")
  (.remove gdom/classes (gdom/getElement "net-container") "hover"))

(defn on-prog-frame-restore [ts prog-frame]
  ; Time-travel to the past if snapshot is available.
  (when-let [ago-ss (get @prog-ss ts)]
    (ago-restore (actx-agw (:world @prog-base)) ago-ss)
    (reset! prog-curr prog-frame)
    (swap! prog-evts #(vec (filter (fn [[s & _]] (<= s ts)) %)))
    (swap! prog-ss #(into {} (filter (fn [[s _]] (<= s ts)) %)))
    (set-el-innerHTML "reqs" (render-reqs-html (:reqs prog-frame)))
    (render-net "net-main" (:net prog-frame) {})
    (on-prog-frame-blur)))

; -------------------------------------------------------------------

(defn render-prog-frame [prog-frame owner]
  (apply dom/ul nil
         (map (fn [[k v]] (dom/li nil (str k ":" (if (number? v) v (count v)))))
              prog-frame)))

(defn render-evts [app owner]
  (apply dom/ul nil
         (map (fn [[ts label prog-frame has-snapshot]]
                (dom/li #js {:className (str "evt evt-" ts)
                             :onMouseEnter #(on-prog-frame-focus @prog-frame)
                             :onMouseLeave #(on-prog-frame-blur)}
                        (if has-snapshot
                          (dom/button
                           #js {:onClick #(on-prog-frame-restore ts @prog-frame)}
                           "rollback")
                          "")
                        (str ts (apply str label))))
              app)))

(defn render-clients [app owner]
  (apply dom/select #js {:id "client"}
         (map (fn [client-addr]
                (dom/option #js {:value client-addr} (str client-addr)))
              (keys (:clients app)))))

(defn init-roots []
  (om/root render-evts prog-evts
           {:target (. js/document (getElementById "evts"))})
  (om/root render-clients prog-curr
           {:target (. js/document (getElementById "controls-clients"))}))

; --------------------------------------------

(defn wait-until [fn]
  (loop []
    (let [res (fn)]
      (if res
        res
        (do (cljs.core.async.impl.dispatch/process-messages)
            (recur))))))

(defn wait-done [done]
  (wait-until #(and (<= (.-length cljs.core.async.impl.dispatch/tasks) 0)
                    @done)))

; ------------------------------------------------

(defn world-vis-init [el-prefix init-event-delay]
  (init-roots)
  (let [req-handlers cbfg.world.lane/cmd-handlers
        req-ch (map< (fn [ev] {:op (.-id (.-target ev))
                               :x (js/parseInt (get-el-value "x"))
                               :y (js/parseInt (get-el-value "y"))
                               :delay (js/parseInt (get-el-value "delay"))
                               :fence (= (get-el-value "fence") "1")
                               :lane (get-el-value "lane")
                               :client (get-el-value "client")
                               :color (get-el-value "color")
                               :sleep (js/parseInt (get-el-value "sleep"))})
                     (merge (map #(listen-el (gdom/getElement %) "click")
                                 (keys req-handlers))))
        event-delay (atom init-event-delay)
        step-ch (chan (dropping-buffer 1))
        prog-ch (listen-el (gdom/getElement "prog-go") "click")
        run-controls-ch (cbfg.vis/vis-run-controls event-delay step-ch "header")]
    (go-loop [num-worlds 0]
      (let [last-id (atom 0)
            gen-id #(swap! last-id inc)
            agw (make-ago-world num-worlds)
            get-agw (fn [] agw)
            event-ch (ago-chan agw)
            event-run-ch (ago-chan agw)
            make-timeout-ch (fn [actx delay] (ago-timeout agw delay))
            ; Init the top actx manually to avoid act recursion.
            w [{:gen-id gen-id
                :get-agw get-agw
                :event-ch event-ch
                :make-timeout-ch make-timeout-ch}]
            world (conj w "world-0")
            vis (atom
                 {:actxs {world {:children {} ; child-actx -> true,
                                 :wait-chs {} ; ch -> [:ghost|:take|:put optional-ch-name],
                                 :collapsed true
                                 ; :loop-state last-loop-bindings,
                                 }}
                  :chs {} ; {ch -> {:id (gen-id), :msgs {msg -> true},
                          ;         :first-taker-actx actx-or-nil}}.
                  :gen-id gen-id})
            net-actx (atom nil)
            net-addrs (atom {})
            net-cb (fn [vis]
                     (when-let [net (get-in vis [:actxs @net-actx :loop-state])]
                       (when (not= net (:net @prog-curr))
                         (swap! prog-curr #(assoc % :net net))
                         (swap! net-addrs #(render-net "net-main" net %)))))]
        (prog-init world)
        (cbfg.vis/process-events vis event-delay cbfg.vis/vis-event-handlers
                                 event-ch step-ch event-run-ch)
        (cbfg.vis/process-render el-prefix world event-run-ch net-cb)
        (cbfg.net/make-net world
                           (:net-listen-ch @prog-base)
                           (:net-connect-ch @prog-base))
        (reset! net-actx
                (first (wait-until #(seq (cbfg.world.net/net-actx-info @vis "net-1")))))
        (let [expand-ch (listen-el (gdom/getElement (str el-prefix "-html")) "click")
              prog-in (get-el-value "prog-in")
              prog-js (str "with (cbfg.world.t1) {" prog-in "}")
              prog-res (try (js/eval prog-js) (catch js/Object ex ex))]
          (go-loop [num-requests 0 num-responses 0]
            (let [[v ch] (alts! [req-ch (:res-ch @prog-base)])
                  ts (inc (:ts @prog-curr))]
              (when v
                (if (= ch req-ch)
                  (if (= (:op v) "replay")
                    (println "TODO-REPLAY-IMPL")
                    (let [req ((get req-handlers (:op v)) (assoc v :opaque ts))]
                      (when-let [client-req-ch
                                 (get-in @prog-curr [:clients (:client req) :req-ch])]
                        (prog-evt world [:req] #(assoc-in % [:reqs ts] [req nil]))
                        (>! client-req-ch req)
                        (recur (inc num-requests) num-responses))))
                  (do (prog-evt world [:res] #(update-in % [:reqs (:opaque v) 1]
                                                           conj [ts v]))
                      (recur num-requests (inc num-responses)))))))
          (go-loop [] ; Process expand/collapse UI events.
            (when-let [ev (<! expand-ch)]
              (let [actx-id (cbfg.vis/no-prefix (.-id (.-target ev)))]
                (doseq [[actx actx-info] (:actxs @vis)]
                  (when (= actx-id (last actx))
                    (swap! vis #(assoc-in % [:actxs actx :collapsed]
                                          (not (:collapsed actx-info))))
                    (>! event-run-ch [@vis nil true nil])))
                (recur))))
          (<! prog-ch)
          (close! expand-ch)
          (close! event-run-ch)
          (close! event-ch)
          (close! (:res-ch @prog-base))
          (recur (inc num-worlds)))))))

; --------------------------------------------

(defn kv-server [server-addr & ports]
  (doseq [port ports]
    (let [world (:world @prog-base)
          done (atom false)]
      (act server-init world
           (when-let [listen-result-ch (achan server-init)]
             (aput server-init (:net-listen-ch @prog-base)
                   [server-addr port listen-result-ch])
             (when-let [[accept-ch close-accept-ch]
                        (atake server-init listen-result-ch)]
               (cbfg.world.net/server-accept-loop world accept-ch close-accept-ch)))
           (reset! done true))
      (wait-done done)
      (prog-evt world [:kv-server server-addr port]
                #(update-in % [:servers server-addr] conj port)))))

(defn kv-client [client-addr server-addr server-port]
  (let [world (:world @prog-base)
        ready (atom false)
        req-ch (cbfg.world.net/client-loop world (:net-connect-ch @prog-base)
                                           server-addr server-port
                                           client-addr (:res-ch @prog-base)
                                           :start-cb #(reset! ready true))]
    (wait-done ready)
    (prog-evt world [:kv-client client-addr server-addr server-port]
              #(assoc-in % [:clients client-addr]
                         {:client-addr client-addr
                          :server-addr server-addr
                          :server-port server-port
                          :req-ch req-ch}))))
