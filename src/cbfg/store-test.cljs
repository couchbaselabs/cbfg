(ns cbfg.store-test
  (:require-macros [cbfg.ago :refer [achan achan-buf aclose
                                     ago ago-loop aput atake atimeout]])
  (:require [cljs.core.async :refer [onto-chan]]
            [cbfg.store :as store]))

(defn test [actx opaque-id]
  (ago test actx "done"))
