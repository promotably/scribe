(defproject scribe "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :profiles {:uberjar {:aot :all}
             :dev {:dependencies [[midje "1.6.3"
                                   :exclusions [joda-time
                                                org.clojure/tools.macro]]]
                   :plugins [[lein-midje "3.0.0"]]}}
  :plugins [[org.clojars.cvillecsteele/lein-git-version "1.0.2"]
            [cider/cider-nrepl "0.8.2"]]
  :dependencies [[org.clojure/clojure "1.6.0"]
                 [amazonica "0.3.6" :exclusions [joda-time]]
                 [clojure.joda-time "0.2.0" :exclusions [joda-time]]
                 [joda-time/joda-time "2.5"]
                 [com.cognitect/transit-clj "0.8.259"]
                 [com.mchange/c3p0 "0.9.5"]
                 [com.stuartsierra/component "0.2.2"]
                 [clj-logging-config "1.9.12"]
                 [log4j/log4j "1.2.17"]
                 [org.clojure/data.json "0.2.5"]
                 [org.clojure/java.jdbc "0.3.6"]
                 [org.clojure/tools.macro "0.1.2"]
                 [org.clojure/tools.logging "0.3.1"]
                 [org.clojure/tools.nrepl "0.2.6"]
                 [org.clojure/tools.cli "0.3.1"]
                 [postgresql "9.3-1102.jdbc4"]
                 [org.apache.commons/commons-daemon "1.0.9"]]
  :main scribe.core
  :repositories {"local" "file:repo"})
