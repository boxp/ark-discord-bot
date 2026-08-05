(ns ark-discord-bot.system.github
    "Integrant component for GitHub API client."
    (:require [ark-discord-bot.effects.github :as github]
              [integrant.core :as ig]))

(defmethod ig/init-key :ark/github-client [_ {:keys [config]}]
           (github/create-client (:github-token config)))

(defmethod ig/halt-key! :ark/github-client [_ _] nil)
