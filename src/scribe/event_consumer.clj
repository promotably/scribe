(ns scribe.event-consumer
  (:require [amazonica.aws.kinesis :as k]
            [clojure.data.json :refer (write-str)]
            [clojure.java.jdbc :as j]
            [clojure.tools.logging :as log]
            [cognitect.transit :as transit]
            [com.stuartsierra.component :as comp])
  (:import [java.nio ByteBuffer]
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

(defn process-message!
  [database {:keys [data sequence-number partition-key] :as message}]
  (log/trace message)
  (try
    (let [{:keys [message-id event-name attributes]} data]
      ;; upsertEvent(_type text, eventId uuid, siteId uuid, shopperId
      ;; uuid, sessionId uuid, promoId uuid, _data json)
      (let [statement (doto (.prepareCall (.getConnection (get-in database [:connection-pool :datasource]))
                                          "SELECT upsertEvent(?,?,?,?,?,?,?);")
                        (.setObject 1 (name event-name))
                        (.setObject 2 message-id)
                        (.setObject 3 (string->uuid (:site-id attributes)))
                        (.setObject 4 (string->uuid (:shopper-id attributes)))
                        (.setObject 5 (string->uuid (:session-id attributes)))
                        (.setObject 6 (string->uuid (:promo-id attributes)))
                        (.setObject 7 (doto (PGobject.)
                                        (.setValue (write-str attributes :value-fn serialize-json))
                                        (.setType "json"))))]
        (.execute statement)))
    (catch java.sql.BatchUpdateException be
      (log/error (.getNextException be)))
    (catch Throwable t
      (log/error t))))


(defn deserialize
  [^java.nio.ByteBuffer byte-buffer]
  (let [ba (byte-array (.remaining byte-buffer))]
    (.get byte-buffer ba)
    (let [in (ByteArrayInputStream. ba)
          rdr (transit/reader in :json)]
      (transit/read rdr))))

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
                                          (log/infof "Processing %d messages"
                                                     (count messages))
                                          (doseq [msg messages]
                                            (log/info msg)
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
