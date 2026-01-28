(ns ark-discord-bot.system.rcon
    "Integrant component for RCON client."
    (:require [ark-discord-bot.effects.rcon :as rcon]
              [integrant.core :as ig]))

(defmethod ig/init-key :ark/rcon-client [_ {:keys [config]}]
           (rcon/create-client (:rcon-host config)
                               (:rcon-port config)
                               (:rcon-password config)))
