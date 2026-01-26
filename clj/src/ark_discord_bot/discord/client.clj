(ns ark-discord-bot.discord.client
    "Discord HTTP API client for sending messages and embeds."
    (:require [babashka.http-client :as http]
              [cheshire.core :as json]))

(def ^:private api-base "https://discord.com/api/v10")

(def ^:private embed-colors
     {:success 0x00FF00
      :error 0xFF0000
      :warning 0xFFFF00
      :info 0x3498DB})

(defn create-client
  "Create a Discord client configuration."
  [token channel-id]
  {:token token :channel-id channel-id})

(defn build-embed
  "Build a Discord embed object."
  [title description embed-type]
  {:title title
   :description description
   :color (get embed-colors embed-type 0x3498DB)})

(defn format-status
  "Format server status for display."
  [status]
  (case status
    :running "🟢 Running"
    :starting "🟡 Starting"
    :not-ready "🟡 Not Ready"
    :error "🔴 Error"
    (str "❓ " (name status))))

(defn- send-request
  "Send HTTP request to Discord API."
  [client method path body]
  (let [url (str api-base path)
        opts {:headers {"Authorization" (str "Bot " (:token client))
                        "Content-Type" "application/json"}
              :body (when body (json/generate-string body))
              :throw false}]
    (case method
      :post (http/post url opts)
      :get (http/get url opts))))

(defn send-message
  "Send a text message to the configured channel."
  [client content]
  (send-request client :post
                (str "/channels/" (:channel-id client) "/messages")
                {:content content}))

(defn send-embed
  "Send an embed message to the configured channel."
  [client embed]
  (send-request client :post
                (str "/channels/" (:channel-id client) "/messages")
                {:embeds [embed]}))

(defn send-status-message
  "Send a server status embed."
  [client status details]
  (let [embed (build-embed "ARK Server Status"
                           (str (format-status status) "\n\n" details)
                           (if (= :running status) :success :warning))]
    (send-embed client embed)))
