(ns ark-discord-bot.system.gateway
    "Integrant component for Discord Gateway connection."
    (:require [ark-discord-bot.effects.gateway :as gateway]
              [integrant.core :as ig]))

(defmethod ig/init-key :ark/gateway [_ {:keys [config gateway-state]}]
           (let [app-events-chan (gateway/connect-with-state (:discord-token config) gateway-state)]
             {:gateway-state gateway-state
              :app-events-chan app-events-chan}))

(defmethod ig/halt-key! :ark/gateway [_ {:keys [gateway-state]}]
           (gateway/shutdown-with-state! gateway-state))
