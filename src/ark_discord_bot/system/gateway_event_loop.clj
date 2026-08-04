(ns ark-discord-bot.system.gateway-event-loop
    "Integrant component for gateway event processing loop."
    (:require [ark-discord-bot.core.commands :as commands]
              [ark-discord-bot.core.status :as status]
              [ark-discord-bot.effects.discord :as discord]
              [ark-discord-bot.effects.gateway :as gateway]
              [ark-discord-bot.effects.github :as github]
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

(defn- handle-pal-update-command [discord-client channel-id]
  (discord/send-pal-update-confirmation discord-client channel-id))

(defn- handle-pal-help-command [discord-client channel-id]
  (discord/send-message discord-client (commands/format-pal-help) channel-id))

(defn- handle-pal-command [cmd discord-client channel-id]
  (case (:command cmd)
    :update (handle-pal-update-command discord-client channel-id)
    :help (handle-pal-help-command discord-client channel-id)
    nil))

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

(defn- call-dispatch-workflow [github-client config]
  (<!! (github/dispatch-workflow github-client (:palserver-repo config)
                                 (:palserver-workflow config) (:palserver-branch config))))

(defn- dispatch-pal-workflow [github-client config]
  (if (nil? (:github-token config))
    (do (log :error "GITHUB_TOKEN not configured") {:error "GITHUB_TOKEN not configured"})
    (let [result (call-dispatch-workflow github-client config)]
      (if (:success result)
        (do (log :info "PalWorld update workflow dispatched successfully") {:success true})
        (do (log :error (str "Failed to dispatch: " (:error result)))
            {:error (:error result)})))))

(defn- pal-update-result-message [result]
  (if (:success result)
    (commands/format-pal-update-success)
    (commands/format-pal-update-failed)))

(defn- execute-pal-update-confirm
  [token interaction-id interaction-token channel-id discord-client github-client config]
  (<!! (discord/respond-to-interaction
        token interaction-id interaction-token
        (discord/build-pal-interaction-update (commands/format-pal-update-started))))
  (let [result (dispatch-pal-workflow github-client config)]
    (<!! (discord/send-message discord-client (pal-update-result-message result) channel-id))))

(defn- execute-pal-update-cancel [token interaction-id interaction-token]
  (<!! (discord/respond-to-interaction
        token interaction-id interaction-token
        (discord/build-pal-interaction-update (commands/format-pal-update-cancelled)))))

(defn- handle-interaction [interaction-data token k8s-client github-client discord-client config]
  (when-let [{:keys [action interaction-id interaction-token channel-id]}
             (gateway/parse-interaction interaction-data)]
    (log :info (str "Interaction: " action))
    (case action
      :restart-confirm (execute-restart-confirm token interaction-id interaction-token k8s-client)
      :restart-cancel (execute-restart-cancel token interaction-id interaction-token)
      :pal-update-confirm (execute-pal-update-confirm token interaction-id interaction-token
                                                      channel-id discord-client github-client config)
      :pal-update-cancel (execute-pal-update-cancel token interaction-id interaction-token)
      nil)))

(defn- log-command-message [content]
  (when (and content (or (re-find #"(?i)^!ark" content)
                         (re-find #"(?i)^!pal" content)))
    (log :debug (str "Received message: " (pr-str content)))))

(defn- warn-empty-content [content is-bot?]
  (when (and (nil? content) (not is-bot?))
    (log :warn (str "Received message with empty content - "
                    "check Message Content Intent in Discord Developer Portal"))))

(defn- try-execute-pal-command [content discord-client channel-id]
  (when-let [cmd (commands/parse-pal-command content)]
    (log :info (str "Pal command: " (:command cmd)))
    (try
      (handle-pal-command cmd discord-client channel-id)
      (catch Exception e
        (log :error (str "Pal command error: " (.getMessage e)))))))

(defn- try-execute-command [content discord-client k8s-client rcon-client config channel-id]
  (or (when-let [cmd (commands/parse-command content)]
        (log :info (str "Command: " (:command cmd)))
        (try
          (handle-command cmd discord-client k8s-client rcon-client config channel-id)
          (catch Exception e
            (log :error (str "Command error: " (.getMessage e))))))
      (try-execute-pal-command content discord-client channel-id)))

(defn- handle-message-event [msg discord-client k8s-client rcon-client config]
  (let [{:keys [content channel_id author]} msg
        is-bot? (:bot author)]
    (when-not is-bot?
      (log-command-message content)
      (warn-empty-content content is-bot?)
      (try-execute-command content discord-client k8s-client
                           rcon-client config channel_id))))

(defn- handle-interaction-event
  [interaction-data token k8s-client github-client discord-client config]
  (try
    (handle-interaction interaction-data token k8s-client github-client discord-client config)
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
                                           (:k8s-client clients) (:github-client clients)
                                           (:discord-client clients) config)
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
                                                          rcon-client github-client config]}]
           (log :info "Starting gateway event loop...")
           (let [shutdown-atom (atom false)
                 clients {:discord-client discord-client
                          :k8s-client k8s-client
                          :rcon-client rcon-client
                          :github-client github-client}
                 control-chan (start-gateway-event-loop (:app-events-chan gateway)
                                                        clients config shutdown-atom)]
             {:control-chan control-chan
              :shutdown-atom shutdown-atom}))

(defmethod ig/halt-key! :ark/gateway-event-loop [_ {:keys [control-chan shutdown-atom]}]
           (reset! shutdown-atom true)
           (async/put! control-chan :stop)
           (async/close! control-chan))
