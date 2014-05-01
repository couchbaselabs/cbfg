(ns cbfg.world.npr
  (:require cbfg.test.npr
            [cbfg.world.base :refer [start-test]]))

; TODO: No standalone world simulation / visualization for npr yet.

(start-test "npr" cbfg.test.npr/test)
