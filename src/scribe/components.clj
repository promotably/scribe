(ns scribe.components
  (:require [amazonica.aws.kinesis :refer (worker)]
            [clojure.tools.logging :as log]
            [clojure.tools.nrepl.server :as nrepl-server]
            [cider.nrepl :refer (cider-nrepl-handler)]
            [clj-logging-config.log4j :as log-config]
            [com.stuartsierra.component :as comp]
            [scribe.config :as config]
            [scribe.event-consumer :as ec])
  (:import [com.mchange.v2.c3p0 ComboPooledDataSource]
           [com.amazonaws.auth.profile ProfileCredentialsProvider]
           [com.amazonaws.auth DefaultAWSCredentialsProviderChain]))

;;;;;;;;;;;;;;;;;;;;;;
;;
;; logging component
;;
;;;;;;;;;;;;;;;;;;;;;;

(defrecord LoggingComponent [config]
  comp/Lifecycle
  (start [this]
    (log-config/set-logger!
     "scribe"
     :name (-> config :logging :name)
     :level (-> config :logging :level)
     :out (-> config :logging :out))
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
                 (.setMaxIdleTime (* 3 60 60)))]
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
;; High Level Application System
;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn system
  [{:keys [port repl-port] :as options}]
  (comp/system-map
   :config   (comp/using (config/map->Config {}) [])
   :logging  (comp/using (map->LoggingComponent {}) [:config])
   :cider    (comp/using (map->ReplComponent {:port repl-port}) [:config :logging])
   :database (comp/using (map->Database {}) [:config :logging])
   :kinesis  (comp/using (map->Kinesis {}) [:config :logging])
   :app      (comp/using (ec/map->EventConsumer {}) [:config :database :kinesis])))

