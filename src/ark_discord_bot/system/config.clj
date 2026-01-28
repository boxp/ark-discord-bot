(ns ark-discord-bot.system.config
    "Integrant component for application configuration."
    (:require [ark-discord-bot.config :as config]
              [integrant.core :as ig]))

(defmethod ig/init-key :ark/config [_ _opts]
           (config/validate-config (config/load-config)))
