(ns cbfg.lane-test
  (:require-macros [cbfg.ago :refer [achan aclose ago ago-loop aput atake]])
  (:require [cbfg.lane :refer [make-lane-pump]]))

(defn e [n result expect result-nil]
  (let [pass (= result expect)
        my-n (swap! n inc)]
    (when (not pass)
      (println (str my-n ":") "FAIL:" result expect))
    (and pass (nil? result-nil))))

(defn make-echo-lane [actx lane-name lane-out-ch]
  (let [lane-in-ch (achan actx)]
    (ago-loop lane-echo actx
              [num-msgs 0]
              (if-let [m (atake lane-echo lane-in-ch)]
                (do (aput lane-echo lane-out-ch [lane-name num-msgs m])
                    (recur (inc num-msgs)))
                (aput lane-echo lane-out-ch :done)))
    lane-in-ch))

(defn test-lane-pump [actx]
  (ago tlp actx
       (let [n (atom 0)
             in-ch (achan actx)
             out-ch (achan actx)
             g (make-lane-pump tlp in-ch out-ch make-echo-lane)]
         (if (and (do (aput tlp in-ch {:lane :a :op :hi :x 1})
                      (e n
                         (atake tlp out-ch)
                         [:a 0 {:lane :a :op :hi :x 1}]
                         nil))
                  (do (aput tlp in-ch {:lane :b :x 2})
                      (e n
                         (atake tlp out-ch)
                         [:b 0 {:lane :b :x 2}]
                         nil))
                  (do (aput tlp in-ch {:x 3})
                      (e n
                         (atake tlp out-ch)
                         [nil 0 {:x 3}]
                         nil))
                  (do (aput tlp in-ch {:lane :a :x 10})
                      (e n
                         (atake tlp out-ch)
                         [:a 1 {:lane :a :x 10}]
                         nil))
                  (do (aput tlp in-ch {:x 30})
                      (e n
                         (atake tlp out-ch)
                         [nil 1 {:x 30}]
                         nil))
                  (do (aput tlp in-ch {:lane :x :op :close-lane})
                      (e n
                         (atake tlp out-ch)
                         {:lane :x :op :close-lane :status :not-found}
                         nil))
                  (do (aput tlp in-ch {:lane :a :x 100})
                      (e n
                         (atake tlp out-ch)
                         [:a 2 {:lane :a :x 100}]
                         nil))
                  (do (aput tlp in-ch {:lane :a :op :close-lane})
                      (e n
                         (atake tlp out-ch)
                         {:lane :a :op :close-lane :status :ok}
                         nil))
                  (e n
                     (atake tlp out-ch) ; For lane :a.
                     :done
                     nil)
                  (do (aput tlp in-ch {:lane :a :x 1000})
                      (e n
                         (atake tlp out-ch)
                         [:a 0 {:lane :a :x 1000}]
                         nil))
                  (do (aclose tlp in-ch)
                      true)
                  (e n
                     (atake tlp out-ch) ; For lane :a.
                     :done
                     nil)
                  (e n
                     (atake tlp out-ch) ; For lane :b.
                     :done
                     nil)
                  (e n
                     (atake tlp out-ch) ; For lane nil.
                     :done
                     nil))
           "pass"
           (str "FAIL: on test-lane #" @n)))))

(defn test [actx opaque]
  (ago test actx
       {:opaque opaque
        :result {"test-lane-pump"
                 (let [ch (test-lane-pump test)
                       cv (atake test ch)
                       _  (atake test ch)]
                   cv)}}))
