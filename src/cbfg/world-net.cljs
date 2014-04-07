(ns cbfg.world-net
  (:require cbfg.net-test
            [cbfg.world-base :refer [start-test]]))

; TODO: No standalone world simulation / visualization for net yet.

(start-test "net-test" cbfg.net-test/test)
