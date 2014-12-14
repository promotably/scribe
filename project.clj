(defproject scribe "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[amazonica "0.3.4"]
                 [clj-logging-config "1.9.12"]
                 [com.cognitect/transit-clj "0.8.259"]
                 [com.mchange/c3p0 "0.9.2.1"]
                 [com.stuartsierra/component "0.2.2"]
                 [log4j/log4j "1.2.17"]
                 [org.clojure/clojure "1.6.0"]
                 [org.clojure/java.jdbc "0.3.6"]
                 [org.clojure/tools.logging "0.3.1"]
                 [postgresql "9.3-1102.jdbc4"]]
  :main scribe.core
  :repositories {"local" "file:repo"})
