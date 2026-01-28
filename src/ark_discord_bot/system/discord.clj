(ns ark-discord-bot.system.discord
    "Integrant component for Discord client."
    (:require [ark-discord-bot.effects.discord :as discord]
              [integrant.core :as ig]))

(defmethod ig/init-key :ark/discord-client [_ {:keys [config]}]
           (discord/create-client (:discord-token config)
                                  (:discord-channel-id config)))
