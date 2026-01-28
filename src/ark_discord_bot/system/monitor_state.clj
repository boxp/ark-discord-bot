(ns ark-discord-bot.system.monitor-state
    "Integrant component for monitor state atom."
    (:require [integrant.core :as ig]))

(defmethod ig/init-key :ark/monitor-state [_ {:keys [config]}]
           (atom {:last-status nil
                  :failure-count 0
                  :failure-threshold (:failure-threshold config)}))
