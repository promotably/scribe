(ns scribe.event-consumer
  (:require
   [clojure.string :refer [upper-case]]
   [amazonica.aws.kinesis :as k]
   [cheshire.core :refer [generate-string]]
   [clojure.data.json :as json]
   [clojure.java.jdbc :as j]
   [clojure.tools.logging :as log]
   [cognitect.transit :as transit]
   [scribe.lib.detector :as detector]
   [scribe.system :refer :all]
   [com.stuartsierra.component :as comp])
  (:import [java.math BigDecimal]
           [java.nio ByteBuffer]
           [java.io ByteArrayInputStream]
           [java.text SimpleDateFormat]
           [org.postgresql.util PGobject]))

(defn- string->uuid
  [s]
  (condp = (class s)
    java.util.UUID s
    java.lang.String (java.util.UUID/fromString s)
    s))

(defn- robot?
  [user-agent-string]
  (if user-agent-string
    (when-let [parsed-user-agent (detector/user-agent user-agent-string)]
      (= (:type parsed-user-agent) :robot))))

(defn- insert-event!
  "Attempts to insert to the events table."
  [database the-event]
  (j/insert! (:connection-pool database)
             :events
             the-event))

(defn- insert-visit-source!
  "Attempts to insert to the visit_sources table."
  [database the-vs]
  (j/insert! (:connection-pool database)
             :visit_sources
             the-vs))

(defn- insert-promo-redemption!
  "Attempts to insert to the promo_redemptions table. If a duplicate (by event_id) is inserted,
   the exception is caught. Any other exceptions are re-thrown"
  [database the-pr]
  (j/insert! (:connection-pool database)
             :promo_redemptions
             the-pr))

(defn- insert-offer-qualification!
  [database the-oq]
  (j/insert! (:connection-pool database)
             :offer_qualifications
             the-oq))

(defn process-sources!
  "If it's an event which might have a sources key, we need to do some additional processing"
  [database cloudwatch-recorder {:keys [message-id event-name attributes] :as data}]
  (let [{:keys [site-id site-shopper-id session-id source]} attributes]
    (when source
      (insert-visit-source! database
                            {:site_id (string->uuid site-id)
                             :site_shopper_id (string->uuid site-shopper-id)
                             :session_id (string->uuid session-id)
                             :data (doto (PGobject.)
                                     (.setValue (generate-string (:data source)))
                                     (.setType "json"))
                             :source_category (-> source :category name)})
      (cloudwatch-recorder "visit-source-inserted" 1 :Count))))

(defn- process-thankyou!
  "If it's a thankyou event, we need to do some additional processing"
  [database cloudwatch-recorder {:keys [message-id event-name attributes] :as data}]
  (let [{:keys [applied-coupons site-id order-id shopper-id site-shopper-id
                session-id control-group]} attributes]
    (doseq [c applied-coupons]
      (let [site-uuid (string->uuid site-id)
            promo-uuid (-> c :promo-uuid string->uuid)
            p (if promo-uuid
                (first (j/query (:connection-pool database)
                                ["select p.id from promos p
                                            join sites s on p.site_id=s.id
                                            where s.site_id=? and p.uuid=?"
                                 site-uuid
                                 promo-uuid])))]
        (insert-promo-redemption! database
                                  {:event_id (string->uuid message-id)
                                   :site_id site-uuid
                                   :order_id order-id
                                   :promo_code (-> c :code upper-case)
                                   :promo_id (if p (:id p))
                                   :discount (BigDecimal. (:discount c))
                                   :shopper_id (string->uuid shopper-id)
                                   :site_shopper_id (string->uuid site-shopper-id)
                                   :session_id (string->uuid session-id)
                                   :control_group (boolean control-group)})
        (cloudwatch-recorder "promo-redemption-inserted" 1 :Count)))))

(defn- process-shopper-qualified-offers!
  [database cloudwatch-recorder {:keys [message-id event-name attributes] :as data}]
  (let [{:keys [site-id shopper-id site-shopper-id offer-ids
                session-id control-group]} attributes]
    (when (seq offer-ids)
      (doseq [oid offer-ids]
        (insert-offer-qualification! database {:event_id (string->uuid message-id)
                                               :site_id (string->uuid site-id)
                                               :site_shopper_id (string->uuid site-shopper-id)
                                               :shopper_id (string->uuid shopper-id)
                                               :session_id (string->uuid session-id)
                                               :offer_id (string->uuid oid)})
        (cloudwatch-recorder "offer-qualification-inserted" 1 :Count)))))

(defn process-message!
  [database cloudwatch-recorder {:keys [data sequence-number partition-key] :as message}]
  (log/trace message)
  (cloudwatch-recorder "event-received" 1 :Count)
  (try
    (let [{{:keys [message-id event-name attributes]} :msg} data
          user-agent-string (or
                             (get-in attributes [:user-agent])
                             (get-in attributes [:request-headers "user-agent"]))]
      (let [the-event {:event_id (string->uuid message-id)
                       :type (name event-name)
                       :site_id (string->uuid (:site-id attributes))
                       :shopper_id (string->uuid (:shopper-id attributes))
                       :site_shopper_id (string->uuid (:site-shopper-id attributes))
                       :session_id (string->uuid (:session-id attributes))
                       :promo_id (string->uuid (:promo-id attributes))
                       :control_group (boolean (:control-group attributes))
                       :data (doto (PGobject.)
                               (.setValue (generate-string attributes))
                               (.setType "json"))}]

        (try
          (if (robot? user-agent-string)
            (do
              (cloudwatch-recorder "robot-event-filtered" 1 :Count)
              (cloudwatch-recorder "robot-event-filtered" 1 :Count
                                   :dimensions {:site-id (str (:site-id attributes))})
              (cloudwatch-recorder "robot-event-filtered" 1 :Count
                                   :dimensions {:site-id (str (:site-id attributes))
                                                :type (name event-name)}))
            (do
              (insert-event! database the-event)
              (cloudwatch-recorder "event-inserted" 1 :Count
                                   :dimensions {:type (name event-name)})
              (when (-> current-system :config :options :debug)
                (log/info event-name))
              (condp = event-name
                :thankyou (process-thankyou! database cloudwatch-recorder data)
                :productview (process-sources! database cloudwatch-recorder data)
                :pageview (process-sources! database cloudwatch-recorder data)
                :shopper-qualified-offers (process-shopper-qualified-offers!
                                           database
                                           cloudwatch-recorder
                                           data)
                nil)))
          (catch org.postgresql.util.PSQLException ex
            (if (seq (re-find #"duplicate key value violates unique constraint\"events_event_id_idx\""
                              (.getMessage ex)))
              (do (log/warnf "Got a duplicate message with ID %s" message-id)
                  (cloudwatch-recorder "event-insert-duplicate" 1 :Count))
              (do (log/errorf ex "Failed to write event %s" (with-out-str (clojure.pprint/pprint the-event)))
                  (cloudwatch-recorder "event-insert-failed" 1 :Count)
                  (cloudwatch-recorder "event-insert-failed" 1 :Count
                                       :dimensions {:site-id (str (:site-id attributes))}))))

          (catch Exception ex
            (cloudwatch-recorder "event-insert-failed" 1 :Count)
            (cloudwatch-recorder "event-insert-failed" 1 :Count
                                 :dimensions {:site-id (str (:site-id attributes))})
            (log/errorf ex "EXCEPTION WHILE PROCESSING MESSAGE %s" (str data))))))))

(defn deserialize
  [^java.nio.ByteBuffer byte-buffer]
  (let [r #"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
        ba (byte-array (.remaining byte-buffer))]
    (.get byte-buffer ba)
    (let [in (ByteArrayInputStream. ba)
          raw (slurp in)]
      (json/read-str raw
                     :key-fn keyword
                     :value-fn (fn [k v]
                                 (if (and (coll? v)
                                          (not (map? v)))
                                   (map (fn [cv]
                                          (if (and (string? cv)
                                                   (re-matches r cv))
                                            (java.util.UUID/fromString cv)
                                            cv)) v)
                                   (if (and (string? v)
                                            (re-matches r v))
                                     (java.util.UUID/fromString v)
                                     v)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
;; Event Consumer
;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defrecord EventConsumer [config kinesis database cloudwatch server]
  comp/Lifecycle
  (start [component]
    (log/info "Starting Event Consumer")
    (let [cloudwatch-recorder (:recorder cloudwatch)
          w (first (k/worker :credentials (:credentials kinesis)
                             :app (get-in config [:event-consumer :app-name])
                             :stream (get-in config [:event-consumer :stream-name])
                             :deserializer deserialize
                             :checkpoint 5
                             :processor (fn [messages]
                                          (log/debugf "Processing %d messages"
                                                      (count messages))
                                          (doseq [msg messages]
                                            (if (= :dev (-> current-system :config :env))
                                              (when (= (-> msg :data :env) (str "dev-" (System/getProperty "user.name")))
                                                (process-message! database
                                                                  cloudwatch-recorder
                                                                  msg))
                                              (process-message! database
                                                                cloudwatch-recorder
                                                                msg))))))
          worker-thread (future (.run w))]
      (log/info "Event Consumer Worker Started")
      (merge component {:worker w
                        :worker-thread worker-thread})))
  (stop [component]
    (log/info "Stopping Event Consumer")
    (if (:worker component)
      (.shutdown (:worker component)))
    (dissoc component :worker)))
