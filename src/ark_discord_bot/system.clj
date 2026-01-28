(ns ark-discord-bot.system
    "System startup and shutdown API using Integrant."
    (:require ;; Require component namespaces for side effects (defmethod registration)
     [ark-discord-bot.system.config]
     [ark-discord-bot.system.discord]
     [ark-discord-bot.system.gateway]
     [ark-discord-bot.system.gateway-event-loop]
     [ark-discord-bot.system.gateway-state]
     [ark-discord-bot.system.kubernetes]
     [ark-discord-bot.system.monitor-loop]
     [ark-discord-bot.system.monitor-state]
     [ark-discord-bot.system.rcon]
     [clojure.java.io :as io]
     [integrant.core :as ig]))

(defn load-config
  "Load Integrant configuration from resources/config.edn."
  []
  (-> (io/resource "config.edn")
      slurp
      ig/read-string))

(defn start!
  "Start the system. Returns the running system map."
  []
  (-> (load-config)
      ig/init))

(defn stop!
  "Stop the system."
  [system]
  (ig/halt! system))
