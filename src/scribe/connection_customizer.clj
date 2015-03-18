(ns scribe.connection-customizer
  (:gen-class :name com.promotably.scribe.ConnectionCustomizer
              :extends com.mchange.v2.c3p0.AbstractConnectionCustomizer
              :state state
              :init init))

(defn -init
  [])

(defn -onCheckOut
  [this ^java.sql.Connection c ^String p]
  (let [s (.createStatement c)]
    (try
      (.executeUpdate s "SET TIME ZONE 'UTC'")
      (finally
        (.close s)))))
