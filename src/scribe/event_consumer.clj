(ns scribe.event-consumer
  (:require [amazonica.aws.kinesis :as k]
            [clojure.tools.logging :as log]
            [cognitect.transit :as transit]
            [com.stuartsierra.component :as comp])
  (:import [java.nio ByteBuffer]
           [java.io ByteArrayInputStream]))


(defn process-message!
  [database {:keys [message-id event-name attributes] :as message}]
  (try
    (catch Throwable t)))


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
                             :deserialize deserialize
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
