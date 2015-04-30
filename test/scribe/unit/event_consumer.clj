(ns scribe.unit.event-consumer
  (:require [midje.sweet :refer :all]
            [scribe.event-consumer :refer :all]))

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
