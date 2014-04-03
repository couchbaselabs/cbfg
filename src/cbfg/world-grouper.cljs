(ns cbfg.world-grouper
  (:require cbfg.grouper-test
            [cbfg.world-base :refer [start-test]]))

; TODO: No standalone world simulation / visualization for grouper yet.

(start-test "grouper-test" cbfg.grouper-test/test)
