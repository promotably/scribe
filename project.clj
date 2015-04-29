(defproject scribe "version placeholder"
  :description "Promotably Scribe"
  :url "https://github.com/promotably/scribe"
  :profiles {:uberjar {:aot :all}
             :dev {:dependencies [[midje "1.6.3"
                                   :exclusions [joda-time
                                                org.clojure/tools.macro]]]
                   :plugins [[lein-midje "3.0.0"]]}}
  :plugins [[org.clojars.cvillecsteele/lein-git-version "1.0.2"]
            [cider/cider-nrepl "0.8.2"]]
  :dependencies [[org.clojure/clojure "1.6.0"]
                 [amazonica "0.3.21" :exclusions [joda-time org.apache.httpcomponents/httpclient]]
                 [clojure.joda-time "0.4.0" :exclusions [joda-time]]
                 [joda-time/joda-time "2.7"]
                 [com.cognitect/transit-clj "0.8.271" :exclusions [commons-codec]]
                 [com.mchange/c3p0 "0.9.5"]
                 [com.stuartsierra/component "0.2.3"]
                 [clj-logging-config "1.9.12"]
                 [log4j/log4j "1.2.17"]
                 [net.logstash.log4j/jsonevent-layout "1.7"]
                 [org.clojure/java.jdbc "0.3.6"]
                 [org.clojure/tools.macro "0.1.5"]
                 [org.clojure/tools.logging "0.3.1"]
                 [org.clojure/tools.nrepl "0.2.10"]
                 [org.clojure/tools.cli "0.3.1"]
                 [org.clojars.promotably/proggly "0.1.8"]
                 [postgresql "9.3-1102.jdbc41"]
                 [org.apache.commons/commons-daemon "1.0.9"]
                 [cheshire "5.4.0"]
                 [org.clojars.promotably/apollo "0.2.4" :exclusions [com.amazonaws/aws-java-sdk]]
                 [compojure "1.3.3" :exclusions [joda-time]]
                 [http-kit "2.1.19"]
                 [net.sf.uadetector/uadetector-resources "2014.10"]]
  :aot [scribe.connection-customizer]
  :main scribe.core)
