(ns cbfg.world-fence
  (:require-macros [cbfg.act :refer [achan-buf]])
  (:require [cljs.core.async :refer [chan]]
            [cbfg.vis :refer [vis-init get-el-value]]
            [cbfg.fence :refer [make-fenced-pump]]
            [cbfg.fence-test]
            [cbfg.world-base :refer [replay-cmd-ch world-cmd-loop start-test]]))

(def cmd-handlers
  {"add"    (fn [c] (assoc c :rq #(cbfg.world-base/example-add % c)))
   "sub"    (fn [c] (assoc c :rq #(cbfg.world-base/example-sub % c)))
   "count"  (fn [c] (assoc c :rq #(cbfg.world-base/example-count % c)))
   "test"   (fn [c] (assoc c :rq #(cbfg.fence-test/test % (:opaque c))))})

(def max-inflight (atom 10))

(defn world-vis-init [el-prefix init-event-delay]
  (let [cmd-inject-ch (chan)
        cmd-ch (replay-cmd-ch cmd-inject-ch (keys cmd-handlers)
                              (fn [ev] {:op (.-id (.-target ev))
                                        :x (js/parseInt (get-el-value "x"))
                                        :y (js/parseInt (get-el-value "y"))
                                        :delay (js/parseInt (get-el-value "delay"))
                                        :fence (= (get-el-value "fence") "1")}))]
    (vis-init (fn [world vis-chs]
                (let [in-ch (achan-buf world 100)
                      out-ch (achan-buf world 0)]
                  (world-cmd-loop world cmd-handlers cmd-ch
                                  in-ch out-ch vis-chs world-vis-init el-prefix)
                  (make-fenced-pump world "main" in-ch out-ch @max-inflight true)))
              el-prefix nil init-event-delay)
    cmd-inject-ch))

(start-test "fence-test" cbfg.fence-test/test)
