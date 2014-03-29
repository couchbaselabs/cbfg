(ns cbfg.world-fence
  (:require-macros [cbfg.ago :refer [ago ago-loop achan-buf aalts aput atake atimeout]])
  (:require [cljs.core.async :refer [chan <! merge map< sliding-buffer]]
            [goog.dom :as gdom]
            [cbfg.vis :refer [vis-init listen-el get-el-value
                              get-el-innerHTML set-el-innerHTML]]
            [cbfg.fence :refer [make-fenced-pump]]))

(defn example-add [actx opaque-id x y delay]
  (ago example-add actx
       (let [timeout-ch (atimeout example-add delay)]
         (atake example-add timeout-ch)
         {:opaque-id opaque-id :result (+ x y)})))

(defn example-sub [actx opaque-id x y delay]
  (ago example-sub actx
       (let [timeout-ch (atimeout example-sub delay)]
         (atake example-sub timeout-ch)
         {:opaque-id opaque-id :result (- x y)})))

(def example-cmd-handlers
  {"add"  (fn [c] {:opaque-id (:opaque-id c) :fence (:fence c)
                   :rq #(example-add % (:opaque-id c) (:x c) (:y c) (:delay c))})
   "sub"  (fn [c] {:opaque-id (:opaque-id c) :fence (:fence c)
                   :rq #(example-sub % (:opaque-id c) (:x c) (:y c) (:delay c))})
   "test" (fn [c] {:opaque-id (:opaque-id c) :fence (:fence c)
                   :rq #(cbfg.fence/test %)})})

(def example-max-inflight (atom 10))

(defn el-log [el v] (set-el-innerHTML el (str v "\n" (get-el-innerHTML el))))

(defn world-vis-init [el-prefix]
  (let [el-input-log (str el-prefix "-input-log")
        el-output-log (str el-prefix "-output-log")
        cmd-ch (map< (fn [ev] {:op (.-id (.-target ev))
                               :x (js/parseInt (get-el-value "x"))
                               :y (js/parseInt (get-el-value "y"))
                               :delay (js/parseInt (get-el-value "delay"))
                               :fence (= (get-el-value "fence") "1")})
                     (merge (map #(listen-el (gdom/getElement %) "click")
                                 (keys example-cmd-handlers))))]
    (vis-init (fn [world]
                (let [in-ch (achan-buf world 100)
                      out-ch (achan-buf world 0)]
                  (ago-loop client world [num-ins 0 num-outs 0]
                            (let [[v ch] (aalts client [cmd-ch out-ch])]
                              (cond
                                (= ch cmd-ch) (let [cmd (assoc-in v [:opaque-id] num-ins)
                                                    cmd-handler ((get example-cmd-handlers (:op cmd)) cmd)]
                                                (el-log el-input-log (str num-ins ": " cmd))
                                                (aput client in-ch cmd-handler)
                                                (recur (inc num-ins) num-outs))
                                (= ch out-ch) (do (el-log el-output-log (str num-outs ": " v))
                                                  (recur num-ins (inc num-outs))))))
                  (make-fenced-pump world in-ch out-ch @example-max-inflight)))
              el-prefix nil)))

(let [last-id (atom 0)
      gen-id #(swap! last-id inc)]
  (ago test-actx [{:gen-id gen-id :event-ch (chan (sliding-buffer 1))}]
       (println (<! (cbfg.fence/test test-actx)))))
