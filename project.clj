(defproject cbfg "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"

  :license {:name "Apache License, Version 2.0"
            :url "http://www.apache.org/licenses/LICENSE-2.0"}

  :dependencies [[org.clojure/clojure "1.5.1"]
                 [org.clojure/clojurescript "0.0-2138"]
                 [org.clojure/core.async "0.1.278.0-76b25b-alpha"]

                 [om "0.2.3"]
                 [sablono "0.2.1"]
                 [com.facebook/react "0.8.0.1"]]

  :plugins [[lein-cljsbuild "1.0.1"]]

  :source-paths ["src"]

  :cljsbuild {
    :builds [{:id "cbfg"
              :source-paths ["src"]
              :compiler {
                :output-to "cbfg.js"
                :output-dir "out"
                :optimizations :none
                :source-map true}}]})
