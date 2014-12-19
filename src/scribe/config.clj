(ns ^{:author "tsteffes@promotably.com"
      :doc "One stop shop for all you application configuration needs"}
  scribe.config
  (:require [com.stuartsierra.component :as component]))


(defn- get-database-config
  "Checks environment variables for database config settings. These
  should always be present on environments deployed to AWS"
  []
  (let [db-host (System/getenv "RDS_HOST")
        db-name (System/getenv "RDS_DB_NAME")
        db-port (if-let [p (System/getenv "RDS_PORT")] (read-string p))
        db-user (System/getenv "RDS_USER")
        db-pwd (System/getenv "RDS_PW")]
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
  (let [sn (System/getenv "KINESIS_A")]
    {:stream-name sn}))


(def app-config
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
   :production {:database (get-database-config)
                :kinesis {}
                :event-consumer (assoc (get-event-consumer-config)
                                  :app-name "production-scribe")
                :env :production}})

(defn lookup
  []
  (let [sys-env (keyword (or (System/getProperty "ENV")
                             (System/getenv "ENV")
                             "dev"))]
    (sys-env app-config)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
;; System component
;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defrecord Config []
  component/Lifecycle
  (start [component]
    (let [m (lookup)]
      (if ((:env m) #{:production :integration})
        (alter-var-root #'*warn-on-reflection* (constantly false))
        (alter-var-root #'*warn-on-reflection* (constantly true)))
      (merge component m)))
  (stop
    [component]
    component))
