(ns ark-discord-bot.system.monitor-state
    "Integrant component for monitor state atom."
    (:require [ark-discord-bot.core.monitor :as monitor]
              [integrant.core :as ig]))

(defmethod ig/init-key :ark/monitor-state [_ {:keys [config]}]
           (atom (monitor/create-state (:failure-threshold config)
                                       (:recovery-cooldown-ms config))))
