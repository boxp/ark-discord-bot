(ns ark-discord-bot.system.kubernetes
    "Integrant component for Kubernetes client."
    (:require [ark-discord-bot.effects.kubernetes :as k8s]
              [integrant.core :as ig]))

(defmethod ig/init-key :ark/k8s-client [_ {:keys [config]}]
           (k8s/create-client (:k8s-namespace config)
                              (:k8s-deployment config)
                              (:k8s-service config)))

(defmethod ig/halt-key! :ark/k8s-client [_ client]
           (when-let [http-client (:http-client client)]
             (.close http-client)))
