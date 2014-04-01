(ns cbfg.fence-test
  (:require-macros [cbfg.ago :refer [achan achan-buf aclose
                                     ago ago-loop aput atake atimeout]])
  (:require [cljs.core.async :refer [onto-chan]]
            [cbfg.fence :refer [make-fenced-pump]]))

(defn test-add-two [actx x delay]
  (ago test-add-two actx
       (let [timeout-ch (atimeout test-add-two delay)]
         (atake test-add-two timeout-ch)
         (+ x 2))))

(defn test-range-to [actx s e delay]
  (let [out (achan actx)]
    (ago test-range-to actx
         (doseq [n (range s e)]
           (let [timeout-ch (atimeout test-range-to delay)]
             (atake test-range-to timeout-ch))
           (aput test-range-to out n))
         (aclose test-range-to out))
    out))

(defn test-helper [actx
                   in-ch-size
                   out-ch-size
                   max-inflight
                   in-msgs]
  (let [in (achan-buf actx in-ch-size)
        out (achan-buf actx out-ch-size)
        fdp (make-fenced-pump actx in out max-inflight)]
    (onto-chan in in-msgs)
    (ago-loop test-out actx [acc nil]
              (let [result (atake test-out out)]
                (if result
                  (recur (conj acc result))
                  (reverse acc))))))

(defn test [actx opaque-id]
  (let [reqs [{:rq #(test-add-two % 1 200)}
              {:rq #(test-add-two % 4 100)}
              {:rq #(test-add-two % 10 50) :fence true}
              {:rq #(test-range-to % 40 45 10) :fence true}
              {:rq #(test-add-two % 9 400)}
              {:rq #(test-add-two % 9 400)}
              {:rq #(test-range-to % 0 2 100)}
              {:rq #(test-range-to % 6 10 5) :fence true}
              {:rq #(test-add-two % 30 100)}]]
    (ago test actx ; TODO - revisit timing wobbles.
         {:opaque-id opaque-id
          :result (map #(cons (if (= (sort (nth % 1))
                                     (sort (nth % 2)))
                                "pass"
                                "FAIL")
                              %)
                       [["test with 2 max-inflight"
                         (let [test-helper-out (test-helper test 100 1 2 reqs)
                               res (atake test test-helper-out)]
                           (atake test test-helper-out)
                           res)
                         '(6 3 12 40 41 42 43 44 11 11 6 7 8 0 1 9 32)]
                        ["test with 200 max-inflight"
                         (let [test-helper-out (test-helper test 100 1 200 reqs)
                               res (atake test test-helper-out)]
                           (atake test test-helper-out)
                           res)
                         '(6 3 12 40 41 42 43 44 6 7 8 0 1 11 11 9 32)]])})))
