(ns ^{:author "tsteffes@promotably.com"
      :doc "One stop shop for all you application configuration needs"}
  scribe.config
  (:require [com.stuartsierra.component :as component]))

;; File-based config data
(def configfile-data {})

(defn- get-config-value
  [key & [default]]
  (or (System/getenv key)
      (System/getProperty key)
      (get configfile-data key default)))

(defn- get-database-config
  "Checks environment variables for database config settings. These
  should always be present on environments deployed to AWS"
  []
  (let [db-host (get-config-value "RDS_HOST")
        db-name (get-config-value "RDS_DB_NAME")
        db-port (if-let [p (get-config-value "RDS_PORT")] (read-string p))
        db-user (get-config-value "RDS_USER")
        db-pwd (get-config-value "RDS_PW")]
    {:db db-name
     :user db-user
     :password db-pwd
     :host db-host
     :port db-port
     :make-pool? true}))

(defn- get-event-consumer-config
  "Checks environment variables for kinesis config settings. These
  should always be present on environments deployed to AWS"
  []
  (let [sn (get-config-value "KINESIS_A")]
    {:stream-name sn}))


(defn app-config
  []
  {:dev        {:database {:db "promotably_dev"
                           :user "p_user"
                           :password "pr0m0"
                           :host "localhost"
                           :port 5432}
                :kinesis {:aws-credentials-profile "promotably"}
                :event-consumer {:stream-name "dev-PromotablyAPIEvents"
                                 :app-name "dev-scribe"}
                :env :dev}
   :test       {:database {:db "promotably_test"
                           :user "p_user"
                           :password "pr0m0"
                           :host "localhost"
                           :port 5432}
                :kinesis  {:aws-credentials-profile "promotably"}
                :event-consumer {:stream-name "dev-PromotablyAPIEvents"
                                 :app-name "test-scribe"}
                :env :test}
   :staging    {:database (get-database-config)
                :kinesis {}
                :event-consumer (assoc (get-event-consumer-config)
                                  :app-name "staging-scribe")
                :env :staging}
   :integration {:database (get-database-config)
                 :kinesis {}
                 :event-consumer (assoc (get-event-consumer-config)
                                   :app-name "integration-scribe")
                 :env :integration}
   :production {:database (get-database-config)
                :kinesis {}
                :event-consumer (assoc (get-event-consumer-config)
                                  :app-name "production-scribe")
                :env :production}})

(defn lookup
  []
  (let [sys-env (keyword (get-config-value "ENV" "dev"))]
    (sys-env (app-config))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
;; System component
;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defrecord Config [config-file]
  component/Lifecycle
  (start [component]
    (if config-file
      (alter-var-root #'configfile-data (-> config-file slurp read-string constantly)))
    (let [m (lookup)]
      (if ((:env m) #{:production :integration})
        (alter-var-root #'*warn-on-reflection* (constantly false))
        (alter-var-root #'*warn-on-reflection* (constantly true)))
      (merge component m)))
  (stop
    [component]
    component))
