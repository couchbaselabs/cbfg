(ns cbfg.world-lane
  (:require-macros [cbfg.ago :refer [ago]])
  (:require [cljs.core.async :refer [chan <! sliding-buffer]]
            cbfg.lane-test))

; TODO: No standalone world simulation / visualization for lane yet.

(let [last-id (atom 0)
      gen-id #(swap! last-id inc)]
  (ago lane-test-actx [{:gen-id gen-id :event-ch (chan (sliding-buffer 1))}]
       (println "lane-test:"
                (<! (cbfg.lane-test/test lane-test-actx 0)))))
