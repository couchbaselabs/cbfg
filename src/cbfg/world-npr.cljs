(ns cbfg.world-npr
  (:require cbfg.npr-test
            [cbfg.world-base :refer [start-test]]))

; TODO: No standalone world simulation / visualization for npr yet.

(start-test "npr-test" cbfg.npr-test/test)
