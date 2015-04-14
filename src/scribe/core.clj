(ns scribe.core
  (:import [org.apache.commons.daemon Daemon DaemonContext])
  (:gen-class :implements [org.apache.commons.daemon.Daemon])
  (:require [com.stuartsierra.component :as c]
            [clojure.tools.cli :refer [parse-opts]]
            scribe.system
            [scribe.components :as components]))

(defn init
  [options]
  (alter-var-root #'scribe.system/current-system (constantly (components/system options))))

(defn start []
  (alter-var-root #'scribe.system/current-system c/start))

(defn stop []
  (alter-var-root #'scribe.system/current-system #(when % (c/stop %) nil)))

(defn go [options]
  (init options)
  (start))

(defn reset [options]
  (stop)
  (go options))

(def cli-options
  [["-p" "--port PORT" "Listening port" :default 9999]
   ["-r" "--repl-port PORT" "Repl / Cider listening port" :default 55555]
   ["-d" "--debug" "Debugging mode"]
   ["-c" "--config-file FILE" "Configuration file name"]
   ;; A non-idempotent option
   ["-v" nil "Verbosity level"
    :id :verbosity
    :default 0
    :assoc-fn (fn [m k _] (update-in m [k] inc))]
   ;; A boolean option defaulting to nil
   ["-h" "--help"]])


;; Daemon implementation

(def daemon-args (atom nil))

(defn -init [this ^DaemonContext context]
  (reset! daemon-args (.getArguments context)))

(defn -start [this]
  (let [{:keys [options summary errors] :as parsed} (parse-opts
                                                     @daemon-args
                                                     cli-options)]
    (go options)))

(defn -stop [this]
    (stop))


;; Main entry point

(defn -main
  "lein run entry point"
  [& args]
  (let [{:keys [options summary errors] :as parsed} (parse-opts args cli-options)]
    (go options)))


;; For REPL development

(comment

  (System/setProperty "ENV" "dev")
  (System/setProperty "ENV" "localdev")
  (System/getProperty "ENV")

  (prn scribe.system/current-system)
  (go {:port 3001 :repl-port 55556})
  (stop)

)
