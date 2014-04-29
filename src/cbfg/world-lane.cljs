(ns cbfg.world-lane
  (:require-macros [cbfg.act :refer [achan-buf]])
  (:require [cljs.core.async :refer [chan]]
            [cbfg.vis :refer [vis-init get-el-value]]
            [cbfg.fence :refer [make-fenced-pump]]
            [cbfg.lane :refer [make-lane-pump]]
            [cbfg.lane-test]
            [cbfg.world-base :refer [replay-cmd-ch world-cmd-loop start-test]]))

(def cmd-handlers
  {"add"   (fn [c] (assoc c :rq #(cbfg.world-base/example-add % c)))
   "sub"   (fn [c] (assoc c :rq #(cbfg.world-base/example-sub % c)))
   "count" (fn [c] (assoc c :rq #(cbfg.world-base/example-count % c)))
   "close-lane" (fn [c] (assoc c :op :close-lane))
   "test"  (fn [c] (assoc c :rq #(cbfg.lane-test/test % (:opaque c))))})

(def max-inflight (atom 10))
(def lane-buf-size (atom 20))

(defn make-fenced-pump-lane [actx lane-name lane-out-ch]
  (let [lane-in-ch (achan-buf actx @lane-buf-size)]
    (make-fenced-pump actx lane-name lane-in-ch lane-out-ch @max-inflight false)
    lane-in-ch))

(defn world-vis-init [el-prefix init-event-delay]
  (let [cmd-inject-ch (chan)
        cmd-ch (replay-cmd-ch cmd-inject-ch (keys cmd-handlers)
                              (fn [ev] {:op (.-id (.-target ev))
                                        :x (js/parseInt (get-el-value "x"))
                                        :y (js/parseInt (get-el-value "y"))
                                        :delay (js/parseInt (get-el-value "delay"))
                                        :fence (= (get-el-value "fence") "1")
                                        :lane (get-el-value "lane")}))]
    (vis-init (fn [world vis-chs]
                (let [in-ch (achan-buf world 100)
                      out-ch (achan-buf world 0)]
                  (world-cmd-loop world cmd-handlers cmd-ch
                                  in-ch out-ch vis-chs world-vis-init el-prefix)
                  (make-lane-pump world in-ch out-ch make-fenced-pump-lane)))
              el-prefix nil init-event-delay)
    cmd-inject-ch))

(start-test "lane-test" cbfg.lane-test/test)
