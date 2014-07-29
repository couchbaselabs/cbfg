(ns cbfg.world.kvs
  (:require cbfg.test.kvs
            [cbfg.world.base :refer [start-test]]))

; TODO: No standalone world simulation / visualization for kvs yet.

(start-test "kvs" cbfg.test.kvs/test)
