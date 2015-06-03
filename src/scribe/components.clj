(ns scribe.components
  (:require [amazonica.aws.kinesis :refer (worker)]
            [clojure.tools.logging :as log]
            [clojure.tools.nrepl.server :as nrepl-server]
            [cider.nrepl :refer (cider-nrepl-handler)]
            [clj-logging-config.log4j :as log-config]
            [com.stuartsierra.component :as comp]
            [scribe.config :as config]
            [scribe.event-consumer :as ec]
            [org.httpkit.server :as http-kit]
            [apollo.core :as apollo])
  (:import [com.mchange.v2.c3p0 ComboPooledDataSource]
           [com.amazonaws.auth.profile ProfileCredentialsProvider]
           [com.amazonaws.auth DefaultAWSCredentialsProviderChain]
           [org.apache.log4j Logger Level]))

;;;;;;;;;;;;;;;;;;;;;;
;;
;; logging component
;;
;;;;;;;;;;;;;;;;;;;;;;

(defrecord LoggingComponent [config]
  comp/Lifecycle
  (start [this]
    (if-let [loggly-url (-> config :logging :loggly-url)]
      (do (log-config/set-loggers!
           :root
           (-> config :logging :base))
          (let [^Logger root-logger (log-config/as-logger :root)
                loggly-appender (doto (org.apache.log4j.AsyncAppender.)
                                  (.setName "async")
                                  (.setLayout (net.logstash.log4j.JSONEventLayoutV1.))
                                  (.setBlocking false)
                                  (.setBufferSize (int 500))
                                  (.addAppender
                                   (doto (com.promotably.proggly.LogglyAppender.)
                                     (.setName "loggly")
                                     (.setLayout
                                      (net.logstash.log4j.JSONEventLayoutV1.))
                                     (.logglyURL loggly-url))))]
            (doto root-logger
              (.addAppender loggly-appender))
            (log/info "Loggly appender is attached?"
                      (.isAttached root-logger loggly-appender))))
      (do
        (log-config/set-loggers!
         :root
         (-> config :logging :base)
         "com.amazonaws"
         (assoc (-> config :logging :base) :level :warn))))
    (log/logf :info "Environment is %s" (-> config :env))
    this)
  (stop [this]
    this))

;;;;;;;;;;;;;;;;;;;;;;
;;
;; repl component
;;
;;;;;;;;;;;;;;;;;;;;;;

(defrecord ReplComponent [port config logging]
  comp/Lifecycle
  (start [this]
    (when ((-> config :env) #{:dev :localdev})
      (log/info (format "Starting cider (nrepl) on %d" port))
      (assoc this :server (clojure.tools.nrepl.server/start-server
                           :port port
                           :handler cider.nrepl/cider-nrepl-handler))))
  (stop [this]
    (if ((-> config :env) #{:dev :localdev})
      (when (:server this)
        (log/info (format "Stopping cider (nrepl)"))
        (clojure.tools.nrepl.server/stop-server (:server this))))
    (dissoc this :server)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
;; Database Component
;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defrecord Database [config logging]
  comp/Lifecycle

  (start [component]
    (let [{{:keys [host port user password db]} :database} config
          cpds (doto (ComboPooledDataSource.)
                 (.setDriverClass "org.postgresql.Driver")
                 (.setJdbcUrl (str "jdbc:postgresql://" host ":" port "/" db))
                 (.setUser user)
                 (.setPassword password)
                 ;; expire excess connections after 30 minutes of inactivity:
                 (.setMaxIdleTimeExcessConnections (* 30 60))
                 ;; expire connections after 3 hours of inactivity:
                 (.setMaxIdleTime (* 3 60 60))
                 (.setConnectionCustomizerClassName "com.promotably.scribe.ConnectionCustomizer"))]
      (log/infof "Starting database component %s %s" host port)
      (assoc component :connection-pool {:datasource cpds})))
  (stop [component]
    (try
      (when-let [cp (:connection-pool component)]
        (.close (:connection-pool cp))
        (dissoc component :connection-pool))
      (catch Exception e
        (log/error e)
        component))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
;; AWS Kinesis Component
;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defrecord Kinesis [config logging]
  comp/Lifecycle
  (start [component]
    (log/info "Starting Kinesis")
    (let [profile (get-in config [:kinesis :aws-credentials-profile])
          ^com.amazonaws.auth.AWSCredentialsProvider cp
          (if-not (nil? profile)
            (ProfileCredentialsProvider. profile)
            (DefaultAWSCredentialsProviderChain.))]
      (assoc component :credentials cp)))
  (stop [component]
    (log/info "Stopping Kinesis")
    (dissoc component :credentials)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
;; AWS Cloudwatch Component
;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defrecord Cloudwatch [config client scheduler recorder]
  comp/Lifecycle
  (start [this]
    (let [{:keys [aws-credential-profile delay-minutes interval-minutes]} (:cloudwatch config)
          c (apollo/create-async-cw-client :provider (if-not (empty? aws-credential-profile)
                                                       (ProfileCredentialsProvider. aws-credential-profile)
                                                       (DefaultAWSCredentialsProviderChain.)))
          s (apollo/create-vacuum-scheduler)
          recorder-namespace (str "scribe-" (name (:env config)))]
      (log/infof "Cloudwatch is starting with credential profile '%s'." aws-credential-profile)
      (apollo/start-vacuum-scheduler! delay-minutes interval-minutes s c)
      (log/infof "Cloudwatch Recording Namespace: %s" recorder-namespace)
      (-> this
          (assoc :client c)
          (assoc :scheduler s)
          (assoc :recorder (apollo/get-context-recorder recorder-namespace {})))))
  (stop [this]
    (log/info "Cloudwatch is stopping")
    (apollo/stop-vacuum-scheduler! (:scheduler this))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
;; Server component
;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn app [req]
  {:status  200
   :headers {"Content-Type" "text/html"}
   :body    "OK"})

(defrecord Server [port config logging]
  comp/Lifecycle
  (start
    [component]
    (if (:stop! component)
      component
      (let [server (http-kit/run-server app {:port (or port 0)})
            port (-> server meta :local-port)]
        (log/info "Web server running on port " port)
        (assoc component :stop! server :port port))))
  (stop
    [component]
    (when-let [stop! (:stop! component)]
      (stop! :timeout 250))
    (dissoc component :stop! :port)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
;; High Level Application System
;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn system
  [{:keys [config-file port repl-port] :as options}]
  (comp/system-map
   :config     (comp/using (config/map->Config {:options options}) [])
   :logging    (comp/using (map->LoggingComponent {}) [:config])
   :cider      (comp/using (map->ReplComponent {:port repl-port}) [:config :logging])
   :database   (comp/using (map->Database {}) [:config :logging])
   :kinesis    (comp/using (map->Kinesis {}) [:config :logging])
   :cloudwatch (comp/using (map->Cloudwatch {}) [:config :logging])
   :server     (comp/using (map->Server {:port (java.lang.Integer. port)}) [:config :logging])
   :app        (comp/using (ec/map->EventConsumer {}) [:config :database :kinesis :cloudwatch :server])))
