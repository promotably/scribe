(ns scribe.event-consumer
  (:require [amazonica.aws.kinesis :as k]
            [clojure.java.jdbc :as j]
            [clojure.tools.logging :as log]
            [cognitect.transit :as transit]
            [com.stuartsierra.component :as comp])
  (:import [java.nio ByteBuffer]
           [java.io ByteArrayInputStream]))


(defn process-message!
  [database {:keys [data sequence-number partition-key] :as message}]
  (try
    (let [{:keys [message-id event-name attributes]} data
          d {:event_id message-id
             :type (name event-name)
             :shopper_id (:shopper-id attributes)
             :session_id (:session-id attributes)
             :site_id (:site-id attributes)
             :promo_id (:promo-id attributes)}]
      (j/insert! (:connection-pool database) :events d))
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

(defrecord EventConsumer [config kinesis database worker worker-thread]
  comp/Lifecycle
  (start [component]
    (log/info "Starting Event Consumer")
    (let [w (first (k/worker :credentials (:credentials kinesis)
                             :app (get-in config [:event-consumer :app-name])
                             :stream (get-in config [:event-consumer :stream-name])
                             :checkpoint false
                             :deserializer deserialize
                             :processor (fn [messages]
                                          (doseq [msg messages]
                                            (process-message! database msg)))))
          t (Thread. w)]
      (.start t)
      (merge component {:worker w
                        :worker-thread t})))
  (stop [component]
    (log/info "Stopping Event Consumer")
    (.shutdown worker)
    (.stop worker-thread)))

(defn event-consumer
  [config]
  (map->EventConsumer {:config config}))
