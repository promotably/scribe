(ns scribe.unit.event-consumer
  (:require [clojure.data.json :as json]
            [cognitect.transit :as transit]
            [midje.sweet :refer :all]
            [scribe.event-consumer :refer :all])
  (:import [java.nio ByteBuffer CharBuffer]
           [java.nio.charset Charset CharsetEncoder]
           [java.io ByteArrayOutputStream]))

(let [site-id (java.util.UUID/randomUUID)
      cw-recorder-args (atom [])
      cw-recorder (fn [& args]
                    (swap! cw-recorder-args conj args))]
  (facts "Robotic events are not saved to the database, and cloudwatch stats are incremented"
    (process-message! {:connection-pool {}}
                      cw-recorder
                      {:data {:message-id (java.util.UUID/randomUUID)
                              :event-name :product-view
                              :attributes {:user-agent "Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)"
                                           :site-id site-id
                                           :shopper-id (java.util.UUID/randomUUID)
                                           :site-shopper-id (java.util.UUID/randomUUID)
                                           :session-id (java.util.UUID/randomUUID)
                                           :control-group "true"}}})
    @cw-recorder-args => (contains [(just ["event-received" 1 :Count])
                                    (just ["robot-event-filtered" 1 :Count])
                                    (just ["robot-event-filtered" 1 :Count
                                           :dimensions (just {:site-id (str site-id)})])
                                    (just ["robot-event-filtered" 1 :Count
                                           :dimensions (just {:site-id (str site-id)
                                                              :type "product-view"})])])))

(fact "Deserialize branching works"
      (let [test-id (java.util.UUID/randomUUID)
            event {:a "A"
                   :b "B"
                   :c test-id}
            event-w-envelope {:msg event}
            charset (Charset/forName "UTF-8")
            encoder (.newEncoder charset)
            raw-bb (.encode encoder (CharBuffer/wrap (json/write-str event-w-envelope :value-fn (fn [k v]
                                                                                                  (if (instance? java.util.UUID v)
                                                                                                    (str v)
                                                                                                    v)))))
            out-stream (ByteArrayOutputStream.)
            t-writer (transit/writer out-stream :json)
            _ (transit/write t-writer event)
            transit-bb (ByteBuffer/wrap (.toByteArray out-stream))]
        (deserialize raw-bb) => {:a "A"
                                 :b "B"
                                 :c test-id}
        (deserialize transit-bb) => {:a "A"
                                     :b "B"
                                     :c test-id}))
