(ns scribe.lib.detector
  (:import
   [net.sf.uadetector.service UADetectorServiceFactory]
   [net.sf.uadetector UserAgent UserAgentType VersionNumber DeviceCategory ReadableDeviceCategory$Category])
  (:require
   [clojure.string :as st]
   [clojure.tools.logging :as log]))

(defrecord Agent [name producer type version device])

(defprotocol ToClojure
  (to-clojure [x]))

(defn- parser []
  (UADetectorServiceFactory/getResourceModuleParser))

(defn user-agent [s]
  (try
    (let [agent-data (to-clojure (.parse (parser) s))]
      (reduce
       (fn [m [k v]] (assoc m k v))
       {}
       agent-data))
    (catch Throwable t
      (log/warn "Unable to parse user agent string %s" s))))

(extend-protocol ToClojure
  DeviceCategory
  (to-clojure [device]
    (condp = (.getCategory device)
      (ReadableDeviceCategory$Category/GAME_CONSOLE) :console
      (ReadableDeviceCategory$Category/OTHER) :other
      (ReadableDeviceCategory$Category/PDA) :pda
      (ReadableDeviceCategory$Category/PERSONAL_COMPUTER) :pc
      (ReadableDeviceCategory$Category/SMART_TV) :tv
      (ReadableDeviceCategory$Category/SMARTPHONE) :phone
      (ReadableDeviceCategory$Category/TABLET) :tablet
      (ReadableDeviceCategory$Category/UNKNOWN) :unknown))
  VersionNumber
  (to-clojure [version]
    (st/join "." (remove st/blank? (.getGroups version))))
  UserAgent
  (to-clojure [agent]
    (Agent. (.getName agent)
            (.getProducer agent)
            (to-clojure (.getType agent))
            (to-clojure (.getVersionNumber agent))
            (to-clojure (.getDeviceCategory agent))))
  UserAgentType
  (to-clojure [type]
    (condp = type
      (UserAgentType/BROWSER) :browser
      (UserAgentType/EMAIL_CLIENT) :email
      (UserAgentType/FEED_READER) :feed-reader
      (UserAgentType/LIBRARY) :library
      (UserAgentType/MEDIAPLAYER) :media-player
      (UserAgentType/MOBILE_BROWSER) :mobile-browser
      (UserAgentType/OFFLINE_BROWSER) :offline-browser
      (UserAgentType/OTHER) :other
      (UserAgentType/ROBOT) :robot
      (UserAgentType/UNKNOWN) :unknown
      (UserAgentType/USERAGENT_ANONYMIZER) :anonymizer
      (UserAgentType/VALIDATOR) :validator
      (UserAgentType/WAP_BROWSER) :wap-browser)))

