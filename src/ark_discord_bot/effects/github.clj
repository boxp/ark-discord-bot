(ns ark-discord-bot.effects.github
    "GitHub API client for triggering workflow dispatches.
   All HTTP functions return core.async channels."
    (:require [babashka.http-client :as http]
              [cheshire.core :as json]
              [clojure.core.async :as async]))

(def ^:private api-base "https://api.github.com")

(defn create-client
  "Create a GitHub client configuration."
  [token]
  {:token token})

(defn- send-request
  "Send HTTP request to GitHub API. Returns a channel."
  [client method path body]
  (async/thread
    (let [url (str api-base path)
          opts {:headers {"Authorization" (str "Bearer " (:token client))
                          "Content-Type" "application/json"
                          "Accept" "application/vnd.github+json"
                          "X-GitHub-Api-Version" "2022-11-28"}
                :body (when body (json/generate-string body))
                :throw false}]
      (case method
        :post (http/post url opts)
        :get (http/get url opts)))))

(defn dispatch-workflow
  "Trigger a workflow_dispatch event for the given repo and workflow file.
   Returns a channel with {:success true} or {:error message}."
  [client repo workflow-file ref]
  (async/go
    (let [path (str "/repos/" repo "/actions/workflows/" workflow-file "/dispatches")
          resp (async/<! (send-request client :post path {:ref ref}))]
      (if (= 204 (:status resp))
        {:success true}
        {:error (str "GitHub API error: " (:status resp) " " (:body resp))}))))
