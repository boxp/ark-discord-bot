(ns ark-discord-bot.system.gateway-event-loop
    "Integrant component for gateway event processing loop."
    (:require [ark-discord-bot.core.commands :as commands]
              [ark-discord-bot.core.status :as status]
              [ark-discord-bot.effects.discord :as discord]
              [ark-discord-bot.effects.gateway :as gateway]
              [ark-discord-bot.effects.kubernetes :as k8s]
              [ark-discord-bot.effects.rcon :as rcon]
              [clojure.core.async :as async :refer [go-loop alt! <!!]]
              [integrant.core :as ig]))

(defn- log [level msg]
  (println (str "[" (name level) "] " msg)))

(defn- safe-disconnect [client]
  (when client
    (try (<!! (rcon/disconnect client)) (catch Exception _))))

(defn- fetch-players-via-rcon [rcon-client timeout-ms]
  (let [connected-client (<!! (rcon/connect rcon-client timeout-ms))]
    (try
      (let [players (rcon/parse-listplayers (<!! (rcon/execute connected-client "ListPlayers")))]
        {:connected true :players players})
      (finally
        (safe-disconnect connected-client)))))

(defn- check-rcon-status [rcon-client timeout-ms]
  (try
    (fetch-players-via-rcon rcon-client timeout-ms)
    (catch Exception e
      (log :warn (str "RCON connection failed: " (type e) " - " (.toString e)
                      " (host=" (:host rcon-client) ", port=" (:port rcon-client) ")"))
      {:connected false :error (.toString e)})))

(defn- check-status [k8s-client rcon-client config]
  (let [k8s-result (try
                     (<!! (k8s/get-deployment-status k8s-client))
                     (catch Exception e
                       {:error (.getMessage e)}))
        rcon-result (when (:available? k8s-result)
                      (check-rcon-status rcon-client (:rcon-timeout config)))]
    (status/determine-status k8s-result rcon-result)))

(defn- handle-help-command [discord-client channel-id]
  (discord/send-message discord-client (commands/format-help) channel-id))

(defn- handle-status-command [discord-client k8s-client rcon-client config channel-id]
  (let [result (check-status k8s-client rcon-client config)]
    (discord/send-status-message discord-client (:status result)
                                 (status/format-status-message result) channel-id)))

(defn- handle-players-command [discord-client rcon-client config channel-id]
  (let [rcon-result (check-rcon-status rcon-client (:rcon-timeout config))
        msg (if (:connected rcon-result)
              (commands/format-players (:players rcon-result []))
              (commands/format-players-error))]
    (discord/send-message discord-client msg channel-id)))

(defn- handle-command [cmd discord-client k8s-client rcon-client config channel-id]
  (case (:command cmd)
    :help (handle-help-command discord-client channel-id)
    :status (handle-status-command discord-client k8s-client rcon-client config channel-id)
    :players (handle-players-command discord-client rcon-client config channel-id)
    :restart (discord/send-restart-confirmation discord-client channel-id)
    nil))

(defn- execute-restart-confirm [token interaction-id interaction-token k8s-client]
  (<!! (discord/respond-to-interaction
        token interaction-id interaction-token
        (discord/build-interaction-update "Restarting ARK server...")))
  (let [result (<!! (k8s/restart-deployment k8s-client))]
    (if (:error result)
      (log :error (str "Failed to restart: " (.getMessage (:error result))))
      (log :info "Server restart initiated successfully"))))

(defn- execute-restart-cancel [token interaction-id interaction-token]
  (<!! (discord/respond-to-interaction
        token interaction-id interaction-token
        (discord/build-interaction-update "ARK server restart cancelled."))))

(defn- handle-interaction [interaction-data token k8s-client]
  (when-let [{:keys [action interaction-id interaction-token]}
             (gateway/parse-interaction interaction-data)]
    (log :info (str "Interaction: " action))
    (case action
      :restart-confirm (execute-restart-confirm token interaction-id
                                                interaction-token k8s-client)
      :restart-cancel (execute-restart-cancel token interaction-id interaction-token)
      nil)))

(defn- log-command-message [content]
  (when (and content (re-find #"(?i)^!ark" content))
    (log :debug (str "Received message: " (pr-str content)))))

(defn- warn-empty-content [content is-bot?]
  (when (and (nil? content) (not is-bot?))
    (log :warn (str "Received message with empty content - "
                    "check Message Content Intent in Discord Developer Portal"))))

(defn- try-execute-command [content discord-client k8s-client rcon-client config channel-id]
  (when-let [cmd (commands/parse-command content)]
    (log :info (str "Command: " (:command cmd)))
    (try
      (handle-command cmd discord-client k8s-client rcon-client config channel-id)
      (catch Exception e
        (log :error (str "Command error: " (.getMessage e)))))))

(defn- handle-message-event [msg discord-client k8s-client rcon-client config]
  (let [{:keys [content channel_id author]} msg
        is-bot? (:bot author)]
    (when-not is-bot?
      (log-command-message content)
      (warn-empty-content content is-bot?)
      (try-execute-command content discord-client k8s-client
                           rcon-client config channel_id))))

(defn- handle-interaction-event [interaction-data token k8s-client]
  (try
    (handle-interaction interaction-data token k8s-client)
    (catch Exception e
      (log :error (str "Interaction error: " (.getMessage e))))))

(defn- handle-ready-event [data]
  (let [user (:user data)
        username (:username user)]
    (log :info (str "Connected to Discord as: " username))
    (log :info "Bot is now ready to receive messages")))

(defn- dispatch-gateway-event [event clients config]
  (case (:type event)
    :message (handle-message-event (:data event) (:discord-client clients)
                                   (:k8s-client clients) (:rcon-client clients) config)
    :interaction (handle-interaction-event (:data event) (:discord-token config)
                                           (:k8s-client clients))
    :ready (handle-ready-event (:data event))
    nil))

(defn- should-continue-event-loop? [event ch shutdown-atom]
  (not (or (nil? event)
           (= :control (first [event ch]))
           @shutdown-atom)))

(defn- process-gateway-event [event ch clients config]
  (try
    (dispatch-gateway-event (second [event ch]) clients config)
    (catch Exception e
      (log :error (str "Event processing error: " (.getMessage e))))))

(defn start-gateway-event-loop
  "Start event loop to process gateway events. Returns control channel."
  [app-events-chan clients config shutdown-atom]
  (let [control-chan (async/chan 1)]
    (go-loop []
      (let [[event ch] (alt! app-events-chan ([e] [:event e])
                             control-chan ([v] [:control v]))]
        (when (should-continue-event-loop? event ch shutdown-atom)
          (process-gateway-event event ch clients config)
          (recur))))
    control-chan))

(defmethod ig/init-key :ark/gateway-event-loop [_ {:keys [gateway discord-client k8s-client
                                                          rcon-client config]}]
           (log :info "Starting gateway event loop...")
           (let [shutdown-atom (atom false)
                 clients {:discord-client discord-client
                          :k8s-client k8s-client
                          :rcon-client rcon-client}
                 control-chan (start-gateway-event-loop (:app-events-chan gateway)
                                                        clients config shutdown-atom)]
             {:control-chan control-chan
              :shutdown-atom shutdown-atom}))

(defmethod ig/halt-key! :ark/gateway-event-loop [_ {:keys [control-chan shutdown-atom]}]
           (reset! shutdown-atom true)
           (async/put! control-chan :stop)
           (async/close! control-chan))
