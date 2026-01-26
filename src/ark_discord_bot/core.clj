(ns ark-discord-bot.core
    "Main entry point for the ARK Discord Bot.
   Orchestrates all components and manages lifecycle."
    (:require [ark-discord-bot.config :as config]
              [ark-discord-bot.discord.client :as discord]
              [ark-discord-bot.discord.commands :as commands]
              [ark-discord-bot.discord.gateway :as gateway]
              [ark-discord-bot.kubernetes.client :as k8s]
              [ark-discord-bot.rcon.client :as rcon]
              [ark-discord-bot.server.monitor :as monitor]
              [ark-discord-bot.server.status-checker :as checker]
              [ark-discord-bot.state :as state]))

(defn- log
  "Simple logging helper."
  [level msg]
  (println (str "[" (name level) "] " msg)))

(defn- check-rcon-status
  "Check RCON connectivity and get player list."
  [rcon-client timeout-ms]
  (let [client (atom nil)]
    (try
      (reset! client (rcon/connect rcon-client timeout-ms))
      (let [players (rcon/parse-listplayers (rcon/execute @client "ListPlayers"))]
        {:connected true :players players})
      (catch Exception e
        {:connected false :error (.getMessage e)})
      (finally
        (when @client
          (try
            (rcon/disconnect @client)
            (catch Exception _)))))))

(defn- check-status
  "Perform full status check."
  [k8s-client rcon-client config]
  (let [k8s-result (try
                     (k8s/get-deployment-status k8s-client)
                     (catch Exception e
                       {:error (.getMessage e)}))
        rcon-result (when (:available? k8s-result)
                      (check-rcon-status rcon-client (:rcon-timeout config)))]
    (checker/determine-status k8s-result rcon-result)))

(defn- handle-command
  "Handle a parsed command."
  [cmd discord-client k8s-client rcon-client config channel-id]
  (case (:command cmd)
    :help
    (discord/send-message discord-client (commands/format-help) channel-id)

    :status
    (let [result (check-status k8s-client rcon-client config)]
      (discord/send-status-message discord-client
                                   (:status result)
                                   (checker/format-status-message result)
                                   channel-id))

    :players
    (let [rcon-result (check-rcon-status rcon-client (:rcon-timeout config))
          msg (if (:connected rcon-result)
                (commands/format-players (:players rcon-result []))
                (commands/format-players-error))]
      (discord/send-message discord-client msg channel-id))

    :restart
    (discord/send-restart-confirmation discord-client channel-id)

    nil))

(defn- handle-interaction
  "Handle Discord interaction (button clicks, etc.)."
  [interaction-data token k8s-client]
  (when-let [interaction (gateway/parse-interaction interaction-data)]
    (let [{:keys [action interaction-id interaction-token]} interaction]
      (log :info (str "Interaction: " action))
      (case action
        :restart-confirm
        (do
         (discord/respond-to-interaction
          token interaction-id interaction-token
          (discord/build-interaction-update
           "🔄 ARKサーバーの再起動を開始しています..."))
         (try
           (k8s/restart-deployment k8s-client)
           (log :info "Server restart initiated successfully")
           (catch Exception e
             (log :error (str "Failed to restart: " (.getMessage e))))))

        :restart-cancel
        (discord/respond-to-interaction
         token interaction-id interaction-token
         (discord/build-interaction-update
          "❌ ARKサーバーの再起動がキャンセルされました。"))

        nil))))

(defn- create-message-handler
  "Create message handler for gateway events."
  [discord-client k8s-client rcon-client config]
  (fn [msg]
    (let [content (:content msg)
          channel-id (:channel_id msg)
          author (:author msg)
          is-bot? (:bot author)]
      ;; Ignore messages from bots (including ourselves)
      (when-not is-bot?
        ;; Log message receipt for debugging (only if it looks like a command)
        (when (and content (re-find #"(?i)^!ark" content))
          (log :debug (str "Received message: " (pr-str content))))
        ;; Warn if content is empty but message looks like it should have content
        (when (and (nil? content) (not is-bot?))
          (log :warn (str "Received message with empty content - "
                          "check Message Content Intent in Discord Developer Portal")))
        (when-let [cmd (commands/parse-command content)]
          (log :info (str "Command: " (:command cmd)))
          (try
            (handle-command cmd discord-client k8s-client
                            rcon-client config channel-id)
            (catch Exception e
              (log :error (str "Command error: " (.getMessage e))))))))))

(defn- start-monitor-loop
  "Start background monitoring loop."
  [discord-client k8s-client rcon-client config]
  (future
   (loop []
     (Thread/sleep (:monitor-interval config))
     (try
       (let [result (check-status k8s-client rcon-client config)
             new-status (:status result)
             monitor-state (state/get-monitor-state)
             is-failure? (not= :running new-status)
             ;; Calculate projected failure count after update
             projected-count (if is-failure?
                               (inc (:failure-count monitor-state))
                               0)
             notify? (monitor/should-notify-with-debounce?
                      monitor-state new-status projected-count)]
         (when notify?
           (log :info (str "Status changed to: " new-status))
           (discord/send-status-message discord-client
                                        new-status
                                        (checker/format-status-message result)))
         (state/update-monitor-state! new-status))
       (catch Exception e
         (when (not (k8s/is-transient-error? e))
           (log :error (str "Monitor error: " (.getMessage e))))))
     (recur))))

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

(defn -main
  "Application entry point."
  [& _args]
  (log :info "ARK Discord Bot starting...")
  (let [config (config/validate-config (config/load-config))
        _ (state/init-state! config)
        discord-client (discord/create-client (:discord-token config)
                                              (:discord-channel-id config))
        k8s-client (k8s/create-client (:k8s-namespace config)
                                      (:k8s-deployment config)
                                      (:k8s-service config))
        rcon-client (rcon/create-client (:rcon-host config)
                                        (:rcon-port config)
                                        (:rcon-password config))
        msg-handler (create-message-handler discord-client k8s-client
                                            rcon-client config)
        interaction-handler (create-interaction-handler (:discord-token config)
                                                        k8s-client)]
    (log :info "Starting monitor loop...")
    (start-monitor-loop discord-client k8s-client rcon-client config)
    (log :info "Connecting to Discord Gateway...")
    (gateway/connect (:discord-token config)
                     msg-handler
                     interaction-handler
                     (create-ready-handler))
    (log :info "Bot is running. Press Ctrl+C to stop.")
    @(promise)))

;; Entry point for bb start
(when (= *file* (System/getProperty "babashka.file"))
  (-main))
