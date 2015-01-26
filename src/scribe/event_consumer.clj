(ns scribe.event-consumer
  (:require [amazonica.aws.kinesis :as k]
            [clojure.data.json :refer (write-str)]
            [clojure.java.jdbc :as j]
            [clojure.tools.logging :as log]
            [cognitect.transit :as transit]
            [com.stuartsierra.component :as comp])
  (:import [java.math BigDecimal]
           [java.nio ByteBuffer]
           [java.io ByteArrayInputStream]
           [java.text SimpleDateFormat]
           [org.postgresql.util PGobject]))

(let [df (SimpleDateFormat. "yyyy-MM-dd'T'HH:mm:ss.SSSZ")]
  (defn- serialize-json
    [key value]
    (condp = (class value)
      java.util.UUID (str value)
      java.util.Date (.format df value)
      value)))

(defn- string->uuid
  [s]
  (condp = (class s)
    java.util.UUID s
    java.lang.String (java.util.UUID/fromString s)
    s))

(defn- record-promo-redemption!
  [conn {:keys [event-id site-id order-id promo-code discount shopper-id
                    site-shopper-id session-id]}]
  (let [statement (doto (.prepareCall conn
                                      "SELECT upsertPromoRedemption(?,?,?,?,?,?,?,?);")
                    (.setObject 1 event-id)
                    (.setObject 2 site-id)
                    (.setObject 3 order-id)
                    (.setObject 4 promo-code)
                    (.setObject 5 discount)
                    (.setObject 6 shopper-id)
                    (.setObject 7 site-shopper-id)
                    (.setObject 8 session-id))]
    (.execute statement)))

(defn- process-thankyou!
  "If it's a thankyou event, we need to do some additional processing"
  [conn {:keys [message-id event-name attributes] :as data}]
  (let [{:keys [applied-coupons site-id order-id shopper-id site-shopper-id session-id]} attributes]
    (doseq [c applied-coupons]
      (record-promo-redemption! conn
                                {:event-id message-id
                                 :site-id (string->uuid site-id)
                                 :order-id order-id
                                 :promo-code (:code c)
                                 :discount (BigDecimal. (:discount c))
                                 :shopper-id (string->uuid shopper-id)
                                 :site-shopper-id (string->uuid site-shopper-id)
                                 :session-id (string->uuid session-id)}))))

(defn process-message!
  [database {:keys [data sequence-number partition-key] :as message}]
  (log/trace message)
  (try
    (let [{:keys [message-id event-name attributes]} data]
      ;; upsertEvent(_type text, eventId uuid, siteId uuid, shopperId
      ;; uuid, sessionId uuid, promoId uuid, _data json)
      (let [conn (.getConnection (get-in database [:connection-pool :datasource]))
            statement (doto (.prepareCall conn
                                          "SELECT upsertEvent(?,?,?,?,?,?,?,?);")
                        (.setObject 1 (name event-name))
                        (.setObject 2 message-id)
                        (.setObject 3 (string->uuid (:site-id attributes)))
                        (.setObject 4 (string->uuid (:shopper-id attributes)))
                        (.setObject 5 (string->uuid (:site-shopper-id attributes)))
                        (.setObject 6 (string->uuid (:session-id attributes)))
                        (.setObject 7 (string->uuid (:promo-id attributes)))
                        (.setObject 8 (doto (PGobject.)
                                        (.setValue (write-str attributes :value-fn serialize-json))
                                        (.setType "json"))))]
        (try
          (.execute statement)
          (when (= event-name :trackthankyou)
            (process-thankyou! conn data))
          (finally
            (.close conn)))))
    (catch java.sql.BatchUpdateException be
      (log/error (.getNextException be)))
    (catch Throwable t
      (log/error t))))


(defn deserialize
  [^java.nio.ByteBuffer byte-buffer]
  (let [ba (byte-array (.remaining byte-buffer))]
    (.get byte-buffer ba)
    (let [in (ByteArrayInputStream. ba)
          rdr (transit/reader in :json)
          d (transit/read rdr)]
      d)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
;; Event Consumer
;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defrecord EventConsumer [config kinesis database]
  comp/Lifecycle
  (start [component]
    (log/info "Starting Event Consumer")
    (let [w (first (k/worker :credentials (:credentials kinesis)
                             :app (get-in config [:event-consumer :app-name])
                             :stream (get-in config [:event-consumer :stream-name])
                             :deserializer deserialize
                             :checkpoint 5
                             :processor (fn [messages]
                                          (log/debugf "Processing %d messages"
                                                     (count messages))
                                          (doseq [msg messages]
                                            (process-message! database msg)))))
          wid @(future (.run w))]
      (log/info "Event Consumer Worker Started With ID %s" wid)
      (merge component {:worker w
                        :worker-id wid})))
  (stop [component]
    (log/info "Stopping Event Consumer")
    (if (:worker component)
      (.shutdown (:worker component)))
    (dissoc component :worker)))
