(ns cbfg.world-grouper
  (:require-macros [cbfg.ago :refer [ago]])
  (:require [cljs.core.async :refer [chan <! sliding-buffer]]
            cbfg.grouper-test))

; TODO: No standalone world simulation / visualization for grouper yet.

(let [last-id (atom 0)
      gen-id #(swap! last-id inc)]
  (ago grouper-test-actx [{:gen-id gen-id :event-ch (chan (sliding-buffer 1))}]
       (println "grouper-test:"
                (<! (cbfg.grouper-test/test grouper-test-actx 0)))))
