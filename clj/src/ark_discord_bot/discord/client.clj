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
    :running "🟢 ARKサーバーは稼働中で接続準備完了です！"
    :starting "🟡 ARKサーバーポッドは稼働中ですが、ゲームサーバーはまだ起動中です。もう少しお待ちください..."
    :not-ready "🟡 ARKサーバーは起動中または準備未完了です..."
    :error "🔴 ARKサーバーでエラーが発生しました！ログを確認してください。"
    (str "❓ 不明なサーバーステータス: " (name status))))

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
  "Send a text message to a channel.
   If channel-id is not provided, uses the client's default channel."
  ([client content]
   (send-message client content (:channel-id client)))
  ([client content channel-id]
   (send-request client :post
                 (str "/channels/" channel-id "/messages")
                 {:content content})))

(defn send-embed
  "Send an embed message to a channel.
   If channel-id is not provided, uses the client's default channel."
  ([client embed]
   (send-embed client embed (:channel-id client)))
  ([client embed channel-id]
   (send-request client :post
                 (str "/channels/" channel-id "/messages")
                 {:embeds [embed]})))

(defn send-status-message
  "Send a server status embed.
   If channel-id is not provided, uses the client's default channel."
  ([client status details]
   (send-status-message client status details (:channel-id client)))
  ([client status details channel-id]
   (let [embed (build-embed "ARK Server Status"
                            (str (format-status status) "\n\n" details)
                            (if (= :running status) :success :warning))]
     (send-embed client embed channel-id))))

(defn build-restart-confirmation
  "Build restart confirmation embed with buttons."
  []
  (let [embed {:title "⚠️ ARKサーバー再起動の確認"
               :description (str "本当にARKサーバーを再起動しますか？\n\n"
                                 "⚠️ **注意**: 再起動中はプレイヤーが切断され、"
                                 "サーバーが再度利用可能になるまで数分かかります。")
               :color 0xFF9900}
        components [{:type 1
                     :components [{:type 2
                                   :style 4
                                   :label "再起動する"
                                   :emoji {:name "🔄"}
                                   :custom_id "restart_confirm"}
                                  {:type 2
                                   :style 2
                                   :label "キャンセル"
                                   :emoji {:name "❌"}
                                   :custom_id "restart_cancel"}]}]]
    {:embed embed :components components}))

(defn build-interaction-response
  "Build interaction response payload."
  [response-type content]
  {:type response-type
   :data {:content content}})

(defn build-interaction-update
  "Build interaction update message response with disabled buttons."
  [content]
  {:type 7  ;; UPDATE_MESSAGE
   :data {:content content
          :components [{:type 1
                        :components [{:type 2
                                      :style 4
                                      :label "再起動する"
                                      :emoji {:name "🔄"}
                                      :custom_id "restart_confirm"
                                      :disabled true}
                                     {:type 2
                                      :style 2
                                      :label "キャンセル"
                                      :emoji {:name "❌"}
                                      :custom_id "restart_cancel"
                                      :disabled true}]}]}})

(defn send-restart-confirmation
  "Send restart confirmation with buttons.
   If channel-id is not provided, uses the client's default channel."
  ([client]
   (send-restart-confirmation client (:channel-id client)))
  ([client channel-id]
   (let [{:keys [embed components]} (build-restart-confirmation)]
     (send-request client :post
                   (str "/channels/" channel-id "/messages")
                   {:embeds [embed] :components components}))))

(defn respond-to-interaction
  "Respond to a Discord interaction.
   Note: Bot token not needed for interaction responses."
  [_token interaction-id interaction-token response]
  (let [url (str api-base "/interactions/" interaction-id "/"
                 interaction-token "/callback")]
    (http/post url {:headers {"Content-Type" "application/json"}
                    :body (json/generate-string response)
                    :throw false})))
