(ns ark-discord-bot.system.gateway-state
    "Integrant component for gateway state atom."
    (:require [integrant.core :as ig]))

(defmethod ig/init-key :ark/gateway-state [_ _opts]
           (atom {:seq nil
                  :running? true
                  :connection-id 0
                  :channels nil
                  :ws-client nil
                  :shutdown-requested? false}))
