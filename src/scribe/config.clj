(ns ^{:author "tsteffes@promotably.com"
      :doc "One stop shop for all you application configuration needs"}
  scribe.config
  (:require [com.stuartsierra.component :as component]))

;; File-based config data
(def ^:dynamic configfile-data {})

(defn- get-config-value
  [key & [default]]
  (or (System/getenv key)
      (System/getProperty key)
      (get configfile-data key default)))

(defn- get-cloudwatch-config
  []
  {:aws-credential-profile (get-config-value "CRED_PROFILE" nil)
   :delay-minutes 1
   :interval-minutes 1})

;; Setup info for logging
(defn base-log-config []
  (if-let [log-dir (get-config-value "LOG_DIR")]
    (let [log-file (str log-dir "/scribe.log")]
      {:name "file"
       :level :info
       :out (org.apache.log4j.DailyRollingFileAppender.
             (net.logstash.log4j.JSONEventLayoutV1.)
             log-file
             "'.'yyyy-MM-dd-HH")})
    {:name "console"
     :level :info
     :out (org.apache.log4j.ConsoleAppender.
           (org.apache.log4j.PatternLayout.
            "%d{HH:mm:ss} %-5p %22.22t %-22.22c{2} %m%n"))}))

(defn loggly-url []
  (get-config-value "LOGGLY_URL"))

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
                :cloudwatch {:aws-credential-profile "promotably"
                             :delay-minutes 1
                             :interval-minutes 1}
                :event-consumer {:stream-name (get-config-value "KINESIS_A" "dev-PromotablyAPIEvents")
                                 :app-name (str "dev-scribe-" (System/getProperty "user.name"))}
                :logging {:base (base-log-config)
                          :loggly-url (loggly-url)}
                :env :dev}
   :test       {:database {:db "promotably_test"
                           :user "p_user"
                           :password "pr0m0"
                           :host "localhost"
                           :port 5432}
                :kinesis  {:aws-credentials-profile "promotably"}
                :cloudwatch {:aws-credential-profile "promotably"
                             :delay-minutes 1
                             :interval-minutes 1}
                :event-consumer {:stream-name (get-config-value "KINESIS_A" "dev-PromotablyAPIEvents")
                                 :app-name "test-scribe"}
                :logging {:base (base-log-config)
                          :loggly-url (loggly-url)}
                :env :test}
   :staging    {:database (get-database-config)
                :kinesis {}
                :cloudwatch (get-cloudwatch-config)
                :event-consumer (assoc (get-event-consumer-config)
                                  :app-name (get-config-value "STACKNAME"))
                :logging {:base (base-log-config)
                          :loggly-url (loggly-url)}
                :env :staging}
   :integration {:database (get-database-config)
                 :kinesis {}
                 :cloudwatch (get-cloudwatch-config)
                 :event-consumer (assoc (get-event-consumer-config)
                                   :app-name (get-config-value "STACKNAME"))
                 :logging {:base (base-log-config)
                           :loggly-url (loggly-url)}
                 :env :integration}
   :production {:database (get-database-config)
                :kinesis {}
                :cloudwatch (get-cloudwatch-config)
                :event-consumer (assoc (get-event-consumer-config)
                                  :app-name (get-config-value "STACKNAME"))
                :logging {:base (base-log-config)
                          :loggly-url (loggly-url)}
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

(defrecord Config [options]
  component/Lifecycle
  (start [component]
    (when (:config-file options)
      (let [data (-> options :config-file slurp read-string)]
        (alter-var-root #'configfile-data (constantly data))))
    (let [m (lookup)]
      (if ((:env m) #{:production :integration})
        (alter-var-root #'*warn-on-reflection* (constantly false))
        (alter-var-root #'*warn-on-reflection* (constantly true)))
      (merge component m)))
  (stop
    [component]
    component))
