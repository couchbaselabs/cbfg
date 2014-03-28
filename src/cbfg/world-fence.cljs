(ns cbfg.world-fence
  (:require-macros [cbfg.ago :refer [ago ago-loop achan-buf aput atake atimeout]])
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

(defn world-vis-init [el-prefix]
  (let [cmd-ch (map< (fn [ev] {:op (.-id (.-target ev))
                               :x (js/parseInt (get-el-value "x"))
                               :y (js/parseInt (get-el-value "y"))
                               :delay (js/parseInt (get-el-value "delay"))
                               :fence (= (get-el-value "fence") "1")})
                     (merge (map #(listen-el (gdom/getElement %) "click")
                                 (keys example-cmd-handlers))))]
    (vis-init (fn [world]
                (let [in-ch (achan-buf world 100)
                      out-ch (achan-buf world 0)]
                  (ago-loop a-input world [num-ins 0]
                            (let [cmd (assoc-in (<! cmd-ch) [:opaque-id] num-ins)
                                  cmd-handler ((get example-cmd-handlers (:op cmd)) cmd)]
                              (set-el-innerHTML (str el-prefix "-input-log")
                                                (str num-ins ": " cmd "\n"
                                                     (get-el-innerHTML (str el-prefix "-input-log"))))
                              (aput a-input in-ch cmd-handler)
                              (recur (inc num-ins))))
                  (ago-loop z-output world [num-outs 0]
                            (let [result (atake z-output out-ch)]
                              (set-el-innerHTML (str el-prefix "-output-log")
                                                (str num-outs ": " result "\n"
                                                     (get-el-innerHTML (str el-prefix "-output-log"))))
                              (recur (inc num-outs))))
                  (make-fenced-pump world in-ch out-ch @example-max-inflight)))
              el-prefix)))

(let [last-id (atom 0)
      gen-id #(swap! last-id inc)]
  (ago test-actx [{:gen-id gen-id :event-ch (chan (sliding-buffer 1))}]
       (println (<! (cbfg.fence/test test-actx)))))
