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
              [ark-discord-bot.server.status-checker :as checker]))

(defn- log
  "Simple logging helper."
  [level msg]
  (println (str "[" (name level) "] " msg)))

(defn- check-rcon-status
  "Check RCON connectivity and get player list."
  [rcon-client timeout-ms]
  (try
    (let [client (rcon/connect rcon-client timeout-ms)
          players (rcon/parse-listplayers (rcon/execute client "ListPlayers"))]
      (rcon/disconnect client)
      {:connected true :players players})
    (catch Exception e
      {:connected false :error (.getMessage e)})))

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
  [cmd discord-client k8s-client rcon-client config _channel-id]
  (case (:command cmd)
    :help
    (discord/send-message discord-client (commands/format-help))

    :status
    (let [result (check-status k8s-client rcon-client config)]
      (discord/send-status-message discord-client
                                   (:status result)
                                   (checker/format-status-message result)))

    :players
    (let [rcon-result (check-rcon-status rcon-client (:rcon-timeout config))
          msg (commands/format-players (:players rcon-result []))]
      (discord/send-message discord-client msg))

    :restart
    (do
     (k8s/restart-deployment k8s-client)
     (discord/send-message discord-client (commands/format-restart-started)))

    nil))

(defn- create-message-handler
  "Create message handler for gateway events."
  [discord-client k8s-client rcon-client config]
  (fn [msg]
    (let [content (:content msg)
          channel-id (:channel_id msg)]
      (when-let [cmd (commands/parse-command content)]
        (log :info (str "Command: " (:command cmd)))
        (try
          (handle-command cmd discord-client k8s-client
                          rcon-client config channel-id)
          (catch Exception e
            (log :error (str "Command error: " (.getMessage e)))))))))

(defn- start-monitor-loop
  "Start background monitoring loop."
  [discord-client k8s-client rcon-client config state-atom]
  (future
   (loop []
     (Thread/sleep (:monitor-interval config))
     (try
       (let [result (check-status k8s-client rcon-client config)
             new-status (:status result)
             state @state-atom
             notify? (monitor/should-notify-with-debounce?
                      state new-status (:failure-count state))]
         (when notify?
           (log :info (str "Status changed to: " new-status))
           (discord/send-status-message discord-client
                                        new-status
                                        (checker/format-status-message result)))
         (swap! state-atom monitor/update-state new-status))
       (catch Exception e
         (when (not (k8s/is-transient-error? e))
           (log :error (str "Monitor error: " (.getMessage e))))))
     (recur))))

(defn -main
  "Application entry point."
  [& _args]
  (log :info "ARK Discord Bot starting...")
  (let [config (config/validate-config (config/load-config))
        discord-client (discord/create-client (:discord-token config)
                                              (:discord-channel-id config))
        k8s-client (k8s/create-client (:k8s-namespace config)
                                      (:k8s-deployment config)
                                      (:k8s-service config))
        rcon-client (rcon/create-client (:rcon-host config)
                                        (:rcon-port config)
                                        (:rcon-password config))
        monitor-state (atom (monitor/create-state (:failure-threshold config)))
        msg-handler (create-message-handler discord-client k8s-client
                                            rcon-client config)]
    (log :info "Starting monitor loop...")
    (start-monitor-loop discord-client k8s-client rcon-client config monitor-state)
    (log :info "Connecting to Discord Gateway...")
    (gateway/connect (:discord-token config) msg-handler)
    (log :info "Bot is running. Press Ctrl+C to stop.")
    @(promise)))

;; Entry point for bb start
(when (= *file* (System/getProperty "babashka.file"))
  (-main))
