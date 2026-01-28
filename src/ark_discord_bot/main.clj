(ns ark-discord-bot.main
    "Main entry point for the ARK Discord Bot.
   Orchestrates all components and manages lifecycle."
    (:require [ark-discord-bot.config :as config]
              [ark-discord-bot.core.commands :as commands]
              [ark-discord-bot.core.monitor :as monitor]
              [ark-discord-bot.core.status :as status]
              [ark-discord-bot.effects.discord :as discord]
              [ark-discord-bot.effects.gateway :as gateway]
              [ark-discord-bot.effects.kubernetes :as k8s]
              [ark-discord-bot.effects.rcon :as rcon]
              [ark-discord-bot.state :as state]
              [clojure.core.async :as async :refer [go-loop alt! timeout <!!]])
    (:gen-class))

(defn- log
  "Simple logging helper."
  [level msg]
  (println (str "[" (name level) "] " msg)))

(defn- safe-disconnect
  "Safely disconnect RCON client, ignoring errors."
  [client]
  (when client
    (try (<!! (rcon/disconnect client)) (catch Exception _))))

(defn- fetch-players-via-rcon
  "Connect to RCON and fetch player list."
  [rcon-client timeout-ms]
  (let [connected-client (<!! (rcon/connect rcon-client timeout-ms))]
    (try
      (let [players (rcon/parse-listplayers (<!! (rcon/execute connected-client "ListPlayers")))]
        {:connected true :players players})
      (finally
        (safe-disconnect connected-client)))))

(defn- check-rcon-status
  "Check RCON connectivity and get player list."
  [rcon-client timeout-ms]
  (try
    (fetch-players-via-rcon rcon-client timeout-ms)
    (catch Exception e
      (log :warn (str "RCON connection failed: " (type e) " - " (.toString e)
                      " (host=" (:host rcon-client) ", port=" (:port rcon-client) ")"))
      {:connected false :error (.toString e)})))

(defn- check-status
  "Perform full status check."
  [k8s-client rcon-client config]
  (let [k8s-result (try
                     (<!! (k8s/get-deployment-status k8s-client))
                     (catch Exception e
                       {:error (.getMessage e)}))
        rcon-result (when (:available? k8s-result)
                      (check-rcon-status rcon-client (:rcon-timeout config)))]
    (status/determine-status k8s-result rcon-result)))

(defn- handle-help-command
  "Handle the help command."
  [discord-client channel-id]
  (discord/send-message discord-client (commands/format-help) channel-id))

(defn- handle-status-command
  "Handle the status command."
  [discord-client k8s-client rcon-client config channel-id]
  (let [result (check-status k8s-client rcon-client config)]
    (discord/send-status-message discord-client (:status result)
                                 (status/format-status-message result) channel-id)))

(defn- handle-players-command
  "Handle the players command."
  [discord-client rcon-client config channel-id]
  (let [rcon-result (check-rcon-status rcon-client (:rcon-timeout config))
        msg (if (:connected rcon-result)
              (commands/format-players (:players rcon-result []))
              (commands/format-players-error))]
    (discord/send-message discord-client msg channel-id)))

(defn- handle-command
  "Handle a parsed command."
  [cmd discord-client k8s-client rcon-client config channel-id]
  (case (:command cmd)
    :help (handle-help-command discord-client channel-id)
    :status (handle-status-command discord-client k8s-client rcon-client config channel-id)
    :players (handle-players-command discord-client rcon-client config channel-id)
    :restart (discord/send-restart-confirmation discord-client channel-id)
    nil))

(defn- execute-restart-confirm
  "Execute the restart confirmation action."
  [token interaction-id interaction-token k8s-client]
  (<!! (discord/respond-to-interaction
        token interaction-id interaction-token
        (discord/build-interaction-update "🔄 ARKサーバーの再起動を開始しています...")))
  (try
    (<!! (k8s/restart-deployment k8s-client))
    (log :info "Server restart initiated successfully")
    (catch Exception e
      (log :error (str "Failed to restart: " (.getMessage e))))))

(defn- execute-restart-cancel
  "Execute the restart cancel action."
  [token interaction-id interaction-token]
  (<!! (discord/respond-to-interaction
        token interaction-id interaction-token
        (discord/build-interaction-update "❌ ARKサーバーの再起動がキャンセルされました。"))))

(defn- handle-interaction
  "Handle Discord interaction (button clicks, etc.)."
  [interaction-data token k8s-client]
  (when-let [{:keys [action interaction-id interaction-token]}
             (gateway/parse-interaction interaction-data)]
    (log :info (str "Interaction: " action))
    (case action
      :restart-confirm (execute-restart-confirm token interaction-id
                                                interaction-token k8s-client)
      :restart-cancel (execute-restart-cancel token interaction-id interaction-token)
      nil)))

(defn- log-command-message
  "Log command message for debugging."
  [content]
  (when (and content (re-find #"(?i)^!ark" content))
    (log :debug (str "Received message: " (pr-str content)))))

(defn- warn-empty-content
  "Warn about empty content from non-bot messages."
  [content is-bot?]
  (when (and (nil? content) (not is-bot?))
    (log :warn (str "Received message with empty content - "
                    "check Message Content Intent in Discord Developer Portal"))))

(defn- try-execute-command
  "Try to parse and execute a command from content."
  [content discord-client k8s-client rcon-client config channel-id]
  (when-let [cmd (commands/parse-command content)]
    (log :info (str "Command: " (:command cmd)))
    (try
      (handle-command cmd discord-client k8s-client rcon-client config channel-id)
      (catch Exception e
        (log :error (str "Command error: " (.getMessage e)))))))

(defn- create-message-handler
  "Create message handler for gateway events."
  [discord-client k8s-client rcon-client config]
  (fn [msg]
    (let [{:keys [content channel_id author]} msg
          is-bot? (:bot author)]
      (when-not is-bot?
        (log-command-message content)
        (warn-empty-content content is-bot?)
        (try-execute-command content discord-client k8s-client
                             rcon-client config channel_id)))))

(defn- calculate-projected-count
  "Calculate projected failure count after status update."
  [monitor-state new-status]
  (if (not= :running new-status)
    (inc (:failure-count monitor-state))
    0))

(defn- notify-status-change
  "Send status change notification if needed."
  [discord-client new-status result]
  (log :info (str "Status changed to: " new-status))
  (discord/send-status-message discord-client new-status
                               (status/format-status-message result)))

(defn- execute-monitor-cycle
  "Execute one cycle of the monitor loop."
  [discord-client k8s-client rcon-client config]
  (let [result (check-status k8s-client rcon-client config)
        new-status (:status result)
        monitor-state (state/get-monitor-state)
        projected-count (calculate-projected-count monitor-state new-status)]
    (when (monitor/should-notify-with-debounce? monitor-state new-status projected-count)
      (notify-status-change discord-client new-status result))
    (state/update-monitor-state! new-status)))

(defn- handle-monitor-error
  "Handle error during monitor cycle."
  [e]
  (when (not (k8s/is-transient-error? e))
    (log :error (str "Monitor error: " (.getMessage e)))))

(defn- start-monitor-loop
  "Start background monitoring loop using core.async.
   Returns the control channel for stopping the loop."
  [discord-client k8s-client rcon-client config]
  (let [control-chan (async/chan 1)]
    (go-loop []
      (let [[_ ch] (alt! (timeout (:monitor-interval config)) [:timeout]
                         control-chan ([v] [:control v]))]
        (when-not (or (= ch control-chan) (state/system-shutdown?))
          (try
            (execute-monitor-cycle discord-client k8s-client rcon-client config)
            (catch Exception e (handle-monitor-error e)))
          (recur))))
    control-chan))

(defn- create-interaction-handler
  "Create interaction handler for button clicks."
  [token k8s-client]
  (fn [interaction-data]
    (try
      (handle-interaction interaction-data token k8s-client)
      (catch Exception e
        (log :error (str "Interaction error: " (.getMessage e)))))))

(defn- create-ready-handler
  "Create handler for READY event."
  []
  (fn [data]
    (let [user (:user data)
          username (:username user)]
      (log :info (str "Connected to Discord as: " username))
      (log :info "Bot is now ready to receive messages"))))

(defn- shutdown-hook
  "Shutdown hook to cleanup resources."
  []
  (log :info "Shutting down...")
  (state/shutdown!)
  ;; Stop monitor loop by sending signal to control channel
  (when-let [control-chan (state/get-monitor-control-chan)]
    (async/put! control-chan :stop)
    (async/close! control-chan))
  ;; Shutdown gateway (closes WebSocket and channels)
  (gateway/shutdown!)
  (log :info "Shutdown complete."))

(defn- initialize-clients
  "Initialize all client instances from config."
  [config]
  {:discord (discord/create-client (:discord-token config) (:discord-channel-id config))
   :k8s (k8s/create-client (:k8s-namespace config) (:k8s-deployment config)
                           (:k8s-service config))
   :rcon (rcon/create-client (:rcon-host config) (:rcon-port config)
                             (:rcon-password config))})

(defn- create-handlers
  "Create message and interaction handlers."
  [clients config]
  {:msg-handler (create-message-handler (:discord clients) (:k8s clients)
                                        (:rcon clients) config)
   :interaction-handler (create-interaction-handler (:discord-token config)
                                                    (:k8s clients))})

(defn- start-services
  "Start monitor loop and gateway connection."
  [clients config handlers]
  (log :info "Starting monitor loop...")
  (state/set-monitor-control-chan!
   (start-monitor-loop (:discord clients) (:k8s clients) (:rcon clients) config))
  (log :info "Connecting to Discord Gateway...")
  ;; connect now returns channels map, ws-client is stored internally
  (gateway/connect (:discord-token config) (:msg-handler handlers)
                   (:interaction-handler handlers)
                   (create-ready-handler)))

(defn- run-main-loop
  "Wait for shutdown signal."
  []
  (log :info "Bot is running. Press Ctrl+C to stop.")
  (while (not (state/system-shutdown?))
         (Thread/sleep 1000)))

(defn -main
  "Application entry point."
  [& _args]
  (log :info "ARK Discord Bot starting...")
  (.addShutdownHook (Runtime/getRuntime) (Thread. shutdown-hook))
  (let [config (config/validate-config (config/load-config))
        _ (state/init-state! config)
        clients (initialize-clients config)
        handlers (create-handlers clients config)]
    (start-services clients config handlers)
    (run-main-loop)))
