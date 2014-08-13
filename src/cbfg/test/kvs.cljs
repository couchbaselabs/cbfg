(ns cbfg.test.kvs
  (:require-macros [cbfg.act :refer [achan aclose act act-loop aput atake]])
  (:require [cbfg.kvs :refer [make-kvs-mgr]]
            [cbfg.misc :refer [dissoc-in]]))

(defn e [n result expect result-nil]
  (let [pass (= result expect)
        my-n (swap! n inc)]
    (when (not pass)
      (println (str my-n ":") "FAIL:" result expect))
    (and pass (nil? result-nil))))

(defn noop-change [kvs new-sq]
  [kvs {:more true :status :ok}])

(defn test-kvs [actx]
  (act tkvs actx
       (let [n (atom 0)
             cmd-ch (make-kvs-mgr tkvs)]
         (if (and
              (let [res-ch (achan tkvs) ; Test missing kvs-ident parameter.
                    m {:opaque @n
                       :res-ch res-ch
                       :op :kvs-remove}]
                (aput tkvs cmd-ch m)
                (e n
                   (atake tkvs res-ch)
                   (merge m {:status :invalid
                             :status-info :missing-kvs-ident-arg})
                   (atake tkvs res-ch)))
              (let [res-ch (achan tkvs) ; Test kvs-list lists nothing.
                    m {:opaque @n
                       :res-ch res-ch
                       :op :kvs-list}]
                (aput tkvs cmd-ch m)
                (e n
                   (atake tkvs res-ch)
                   (merge m {:status :ok})
                   (atake tkvs res-ch)))
              (let [res-ch (achan tkvs) ; Test not-found kvs-ident.
                    m {:opaque @n
                       :res-ch res-ch
                       :op :kvs-remove
                       :kvs-ident [:bogus :ident]}]
                (aput tkvs cmd-ch m)
                (e n
                   (atake tkvs res-ch)
                   (merge m {:status :not-found
                             :status-info [:no-kvs (:kvs-ident m)]})
                   (atake tkvs res-ch)))
              (let [res-ch (achan tkvs) ; Test create of kvs.
                    m {:opaque @n
                       :res-ch res-ch
                       :op :kvs-open
                       :name :foo}]
                (aput tkvs cmd-ch m)
                (let [res (atake tkvs res-ch)]
                  (and
                   (e n res
                      (merge m {:status :ok
                                :status-info :created
                                :kvs-ident (:kvs-ident res)})
                      (atake tkvs res-ch))
                   (let [res-ch (achan tkvs) ; Test kvs-lists lists :foo.
                         m {:opaque @n
                            :res-ch res-ch
                            :op :kvs-list}]
                     (aput tkvs cmd-ch m)
                     (and (e n
                             (atake tkvs res-ch)
                             (merge m {:more true :status :ok :name :foo})
                             nil)
                          (e n
                             (atake tkvs res-ch)
                             (merge m {:status :ok})
                             (atake tkvs res-ch))))
                   (let [res-ch2 (achan tkvs) ; Test reopen of kvs.
                         m2 {:opaque @n
                             :res-ch res-ch2
                             :op :kvs-open
                             :name :foo}]
                     (aput tkvs cmd-ch m2)
                     (let [res2 (atake tkvs res-ch2)]
                       (e n res2
                          (merge m2 {:status :ok
                                     :status-info :reopened
                                     :kvs-ident (:kvs-ident res)})
                          (atake tkvs res-ch2))))
                   (let [res-ch (achan tkvs) ; Test bogus kvs is still not-found.
                         m {:opaque @n
                            :res-ch res-ch
                            :op :kvs-remove
                            :kvs-ident [:bogus :ident]}]
                     (aput tkvs cmd-ch m)
                     (e n
                        (atake tkvs res-ch)
                        (merge m {:status :not-found
                                  :status-info [:no-kvs (:kvs-ident m)]})
                        (atake tkvs res-ch)))
                   (let [res-ch3 (achan tkvs) ; Test remove of kvs.
                         m3 {:opaque @n
                             :res-ch res-ch3
                             :op :kvs-remove
                             :kvs-ident (:kvs-ident res)}]
                     (aput tkvs cmd-ch m3)
                     (let [res3 (atake tkvs res-ch3)]
                       (e n res3
                          (merge m3 {:status :ok
                                     :status-info :removed})
                                  (atake tkvs res-ch3))))
                   (let [res-ch4 (achan tkvs) ; Test re-remove of kvs fails.
                         m4 {:opaque @n
                             :res-ch res-ch4
                             :op :kvs-remove
                             :kvs-ident (:kvs-ident res)}]
                     (aput tkvs cmd-ch m4)
                     (let [res4 (atake tkvs res-ch4)]
                       (e n res4
                          (merge m4 {:status :not-found
                                     :status-info [:no-kvs (:kvs-ident res)]})
                          (atake tkvs res-ch4))))
                   (let [res-ch5 (achan tkvs) ; Test multi-get on non-existent kvs.
                         m5 {:opaque @n
                             :res-ch res-ch5
                             :op :multi-get
                             :kvs-ident (:kvs-ident res)}]
                     (aput tkvs cmd-ch m5)
                     (let [res5 (atake tkvs res-ch5)]
                       (e n res5
                          (merge m5 {:status :not-found
                                     :status-info [:no-kvs (:kvs-ident res)]})
                          (atake tkvs res-ch5))))
                   (let [res-ch (achan tkvs) ; Test list names is still empty.
                         m {:opaque @n
                            :res-ch res-ch
                            :op :kvs-list}]
                     (aput tkvs cmd-ch m)
                     (e n
                        (atake tkvs res-ch)
                        (merge m {:status :ok})
                        (atake tkvs res-ch)))
                   )))
              (let [open-ch (achan tkvs) ; Create a new kvs0.
                    m {:opaque @n
                       :res-ch open-ch
                       :op :kvs-open
                       :name :kvs0}]
                (aput tkvs cmd-ch m)
                (let [open (atake tkvs open-ch)]
                  (and
                   (e n open
                      (merge m {:status :ok
                                :status-info :created
                                :kvs-ident (:kvs-ident open)})
                      (atake tkvs open-ch))
                   (let [res-ch2 (achan tkvs) ; Test multi-get with empty keys.
                         m2 {:opaque @n
                             :res-ch res-ch2
                             :op :multi-get
                             :kvs-ident (:kvs-ident open)
                             :keys []}]
                     (aput tkvs cmd-ch m2)
                     (let [res2 (atake tkvs res-ch2)]
                       (e n res2
                          (dissoc (merge m2 {:status :ok})
                                  :keys)
                          (atake tkvs res-ch2))))
                   (let [res-ch3 (achan tkvs) ; Test multi-change with empty changes.
                         m3 {:opaque @n
                             :res-ch res-ch3
                             :op :multi-change
                             :kvs-ident (:kvs-ident open)
                             :changes []}]
                     (aput tkvs cmd-ch m3)
                     (let [res3 (atake tkvs res-ch3)]
                       (e n res3
                          (dissoc (merge m3 {:status :ok})
                                  :changes)
                          (atake tkvs res-ch3))))
                   (let [res-ch4 (achan tkvs) ; Test multi-change with noop-change.
                         m4 {:opaque @n
                             :res-ch res-ch4
                             :op :multi-change
                             :kvs-ident (:kvs-ident open)
                             :changes [noop-change]}]
                     (aput tkvs cmd-ch m4)
                     (and
                      (e n (atake tkvs res-ch4)
                         (dissoc (merge m4 {:more true :status :ok})
                                 :changes)
                         nil)
                      (e n (atake tkvs res-ch4)
                         (dissoc (merge m4 {:status :ok})
                                 :changes)
                         (atake tkvs res-ch4))))
                   (let [res-ch5 (achan tkvs) ; Test several noop-changes.
                         m5 {:opaque @n
                             :res-ch res-ch5
                             :op :multi-change
                             :kvs-ident (:kvs-ident open)
                             :changes [noop-change noop-change noop-change]}]
                     (aput tkvs cmd-ch m5)
                     (and
                      (e n (atake tkvs res-ch5)
                         (dissoc (merge m5 {:more true :status :ok})
                                 :changes)
                         nil)
                      (e n (atake tkvs res-ch5)
                         (dissoc (merge m5 {:more true :status :ok})
                                 :changes)
                         nil)
                      (e n (atake tkvs res-ch5)
                         (dissoc (merge m5 {:more true :status :ok})
                                 :changes)
                         nil)
                      (e n (atake tkvs res-ch5)
                         (dissoc (merge m5 {:status :ok})
                                 :changes)
                         (atake tkvs res-ch5))))
                   (let [res-ch6 (achan tkvs) ; Test simple entry insert.
                         m6 {:opaque @n
                             :res-ch res-ch6
                             :op :multi-change
                             :kvs-ident (:kvs-ident open)
                             :changes [(cbfg.kvs/mutate-entry
                                        {:key :a :val :A})]}]
                     (aput tkvs cmd-ch m6)
                     (let [res6 (atake tkvs res-ch6)]
                       (and
                        (:sq res6)
                        (e n res6
                           (dissoc (merge m6 {:more true
                                              :status :ok
                                              :key :a
                                              :sq (:sq res6)})
                                   :changes)
                           nil)
                        (e n (atake tkvs res-ch6)
                           (dissoc (merge m6 {:status :ok})
                                   :changes)
                           (atake tkvs res-ch6))
                        (let [res-ch7 (achan tkvs) ; Test multi-get returns 1 entry.
                              m7 {:opaque @n
                                  :res-ch res-ch7
                                  :op :multi-get
                                  :kvs-ident (:kvs-ident open)
                                  :keys [:a]}]
                          (aput tkvs cmd-ch m7)
                          (let [res7 (atake tkvs res-ch7)]
                            (and
                             (e n res7
                                (dissoc (merge m7 {:more true
                                                   :status :ok
                                                   :key :a
                                                   :entry {:key :a :val :A
                                                           :sq (:sq res6)}})
                                        :keys)
                                nil)
                             (e n (atake tkvs res-ch7)
                                (dissoc (merge m7 {:status :ok})
                                        :keys)
                                (atake tkvs res-ch7)))))
                        (let [res-ch7 (achan tkvs) ; Test multi-get of several entries.
                              m7 {:opaque @n
                                  :res-ch res-ch7
                                  :op :multi-get
                                  :kvs-ident (:kvs-ident open)
                                  :keys [:a :a :a]}]
                          (aput tkvs cmd-ch m7)
                          (and
                           (e n (atake tkvs res-ch7)
                              (dissoc (merge m7 {:more true :status :ok
                                                 :key :a
                                                 :entry {:key :a :val :A
                                                         :sq (:sq res6)}})
                                      :keys)
                              nil)
                           (e n (atake tkvs res-ch7)
                              (dissoc (merge m7 {:more true :status :ok
                                                 :key :a
                                                 :entry {:key :a :val :A
                                                         :sq (:sq res6)}})
                                      :keys)
                              nil)
                           (e n (atake tkvs res-ch7)
                              (dissoc (merge m7 {:more true :status :ok
                                                 :key :a
                                                 :entry {:key :a :val :A
                                                         :sq (:sq res6)}})
                                      :keys)
                              nil)
                           (e n (atake tkvs res-ch7)
                              (dissoc (merge m7 {:status :ok})
                                      :keys)
                              (atake tkvs res-ch7))))
                        (let [res-ch7bs (achan tkvs) ; Test change with bad sq.
                              m7bs {:opaque @n
                                    :res-ch res-ch7bs
                                    :op :multi-change
                                    :kvs-ident (:kvs-ident open)
                                    :changes [(cbfg.kvs/mutate-entry
                                               {:key :a
                                                :val :will-be-rejected
                                                :sq :some-wrong-sq})]}]
                          (aput tkvs cmd-ch m7bs)
                          (let [res7bs (atake tkvs res-ch7bs)]
                            (and
                             (e n res7bs
                                (dissoc (merge m7bs
                                               {:more true
                                                :status :mismatch
                                                :status-info [:wrong-sq
                                                               :some-wrong-sq]
                                                :key :a})
                                        :changes)
                                nil)
                             (e n (atake tkvs res-ch7bs)
                                (dissoc (merge m7bs {:status :ok})
                                        :changes)
                                (atake tkvs res-ch7bs)))))
                        (let [res-ch7c (achan tkvs) ; Test change with right sq.
                              m7c {:opaque @n
                                   :res-ch res-ch7c
                                   :op :multi-change
                                   :kvs-ident (:kvs-ident open)
                                   :changes [(cbfg.kvs/mutate-entry
                                              {:key :a
                                               :val :AA
                                               :sq (:sq res6)})]}]
                          (aput tkvs cmd-ch m7c)
                          (let [res7c (atake tkvs res-ch7c)]
                            (and
                             (:sq res7c)
                             (e n res7c
                                (dissoc (merge m7c
                                               {:more true
                                                :status :ok
                                                :key :a
                                                :sq (:sq res7c)})
                                        :changes)
                                nil)
                             (e n (atake tkvs res-ch7c)
                                (dissoc (merge m7c {:status :ok})
                                        :changes)
                                (atake tkvs res-ch7c))
                             (let [res-ch7g (achan tkvs) ; Test change had effect.
                                   m7g {:opaque @n
                                        :res-ch res-ch7g
                                        :op :multi-get
                                        :kvs-ident (:kvs-ident open)
                                        :keys [:a]}]
                               (aput tkvs cmd-ch m7g)
                               (let [res7g (atake tkvs res-ch7g)]
                                 (and
                                  (e n res7g
                                     (dissoc (merge m7g {:more true
                                                         :status :ok
                                                         :key :a
                                                         :entry {:key :a :val :AA
                                                                 :sq (:sq res7c)}})
                                             :keys)
                                     nil)
                                  (e n (atake tkvs res-ch7g)
                                     (dissoc (merge m7g {:status :ok})
                                             :keys)
                                     (atake tkvs res-ch7g)))))
                             )))
                        )))
                   (let [res-ch8 (achan tkvs) ; Test multi-get of missing keys.
                         m8 {:opaque @n
                             :res-ch res-ch8
                             :op :multi-get
                             :kvs-ident (:kvs-ident open)
                             :keys [:does-not-exist :not-there]}]
                     (aput tkvs cmd-ch m8)
                     (and
                      (e n (atake tkvs res-ch8)
                         (dissoc (merge m8 {:more true
                                            :status :not-found
                                            :key :does-not-exist})
                                 :keys)
                         nil)
                      (e n (atake tkvs res-ch8)
                         (dissoc (merge m8 {:more true
                                            :status :not-found
                                            :key :not-there})
                                 :keys)
                         nil)
                      (e n (atake tkvs res-ch8)
                         (dissoc (merge m8 {:status :ok})
                                 :keys)
                         (atake tkvs res-ch8))))
                   (let [res-ch9 (achan tkvs) ; Test delete with wrong sq.
                         m9 {:opaque @n
                             :res-ch res-ch9
                             :op :multi-change
                             :kvs-ident (:kvs-ident open)
                             :changes [(cbfg.kvs/mutate-entry {:key :a :deleted true
                                                               :sq :not-a-sq-match})]}]
                     (aput tkvs cmd-ch m9)
                     (let [res9 (atake tkvs res-ch9)]
                       (and (e n res9
                               (dissoc (merge m9 {:more true
                                                  :status :mismatch
                                                  :status-info [:wrong-sq
                                                                :not-a-sq-match]
                                                  :key :a})
                                       :changes)
                               nil)
                            (e n (atake tkvs res-ch9)
                               (dissoc (merge m9 {:status :ok})
                                       :changes)
                               (atake tkvs res-ch9)))))
                   (let [res-ch9 (achan tkvs) ; Test delete with no sq.
                         m9 {:opaque @n
                             :res-ch res-ch9
                             :op :multi-change
                             :kvs-ident (:kvs-ident open)
                             :changes [(cbfg.kvs/mutate-entry {:key :a
                                                               :deleted true})]}]
                     (aput tkvs cmd-ch m9)
                     (let [res9 (atake tkvs res-ch9)]
                       (and (:sq res9)
                            (e n res9
                               (dissoc (merge m9 {:more true
                                                  :status :ok
                                                  :key :a
                                                  :sq (:sq res9)})
                                       :changes)
                               nil)
                            (e n (atake tkvs res-ch9)
                               (dissoc (merge m9 {:status :ok})
                                       :changes)
                               (atake tkvs res-ch9))
                            (let [res-ch9a (achan tkvs) ; Test delete took effect.
                                  m9a {:opaque @n
                                       :res-ch res-ch9a
                                       :op :multi-get
                                       :kvs-ident (:kvs-ident open)
                                       :keys [:a]}]
                              (aput tkvs cmd-ch m9a)
                              (let [res9a (atake tkvs res-ch9a)]
                                (and
                                 (e n res9a
                                    (dissoc (merge m9a {:more true
                                                        :status :not-found
                                                        :key :a})
                                            :keys)
                                    nil)
                                 (e n (atake tkvs res-ch9a)
                                    (dissoc (merge m9a {:status :ok})
                                            :keys)
                                    (atake tkvs res-ch9a))))))))
                   ))))
           "pass"
           (str "FAIL: on test-lane #" @n)))))

(defn test [actx opaque]
  (act test actx
       {:opaque opaque
        :result {"test-kvs"
                 (let [ch (test-kvs test)
                       cv (atake test ch)
                       _  (atake test ch)]
                   cv)}}))

