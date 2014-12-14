(ns scribe.components
  (:require [amazonica.aws.kinesis :refer (worker)]
            [clojure.tools.logging :as log]
            [com.stuartsierra.component :as comp]
            [scribe.config :as cfg]
            [scribe.event-consumer :refer (event-consumer)])
  (:import [com.mchange.v2.c3p0 ComboPooledDataSource]
           [com.amazonaws.auth.profile ProfileCredentialsProvider]
           [com.amazonaws.auth DefaultAWSCredentialsProviderChain]))

(def system nil)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
;; Database Component
;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defrecord Database [host port user password db connection-pool]
  comp/Lifecycle

  (start [component]
    (let [cpds (doto (ComboPooledDataSource.)
                 (.setDriverClass "org.postgresql.Driver")
                 (.setJdbcUrl (str "jdbc:postgresql://" host ":" port "/" db))
                 (.setUser user)
                 (.setPassword password)
                 ;; expire excess connections after 30 minutes of inactivity:
                 (.setMaxIdleTimeExcessConnections (* 30 60))
                 ;; expire connections after 3 hours of inactivity:
                 (.setMaxIdleTime (* 3 60 60)))]
      (assoc component :connection-pool {:datasource cpds})))
  (stop [component]
    (try
      (.close connection-pool)
      (catch Exception e
        (log/error e)))))

(defn new-database
  [host port user password name]
  (map->Database {:host host :port port :user user :password password :name name}))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
;; AWS Kinesis Component
;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defrecord Kinesis [config credentials]
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
    (log/info "Stopping Kinesis")))

(defn new-kinesis
  [config]
  (map->Kinesis {:config config}))


(defn new-system
  [{{host :host port :port user :user password :password db :db} :database :as config}]
  (comp/system-map
   :database (new-database host port user password db)
   :kinesis (new-kinesis config)
   :app (comp/using
         (event-consumer config)
         [:database :kinesis])))

(defn init
  []
  (let [c (cfg/lookup)]
    (log/info c)
    (alter-var-root #'system
                    (constantly (new-system c)))))

(defn start
  []
  (alter-var-root #'system comp/start))

(defn stop
  []
  (alter-var-root #'system
                  (fn [s] (when s (comp/stop s)))))

(defn go
  []
  (init)
  (start))
