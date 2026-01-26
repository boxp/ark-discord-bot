(ns ark-discord-bot.config
    "Configuration management for the ARK Discord Bot.
   Reads configuration from environment variables.")

(defn get-env
  "Get environment variable with optional default value."
  ([key] (get-env key nil))
  ([key default] (or (System/getenv key) default)))

(defn get-required-env
  "Get required environment variable. Throws if not set."
  [key]
  (or (System/getenv key)
      (throw (ex-info (str "Required env var not set: " key)
                      {:key key}))))

(defn parse-int
  "Parse string to integer with optional default."
  ([s] (parse-int s nil))
  ([s default]
   (if (some? s)
     (try (Integer/parseInt s) (catch Exception _ default))
     default)))

(def default-config
     "Default configuration values."
     {:rcon-port 27020
      :rcon-timeout 10000
      :monitor-interval 60000
      :failure-threshold 3
      :log-level "INFO"})

(defn load-config
  "Load configuration from environment variables."
  []
  {:discord-token (get-required-env "DISCORD_TOKEN")
   :discord-channel-id (get-required-env "DISCORD_CHANNEL_ID")
   :k8s-namespace (get-env "K8S_NAMESPACE" "default")
   :k8s-deployment (get-required-env "K8S_DEPLOYMENT_NAME")
   :k8s-service (get-required-env "K8S_SERVICE_NAME")
   :rcon-host (get-env "RCON_HOST" "localhost")
   :rcon-port (parse-int (get-env "RCON_PORT")
                         (:rcon-port default-config))
   :rcon-password (get-required-env "RCON_PASSWORD")
   :rcon-timeout (parse-int (get-env "RCON_TIMEOUT")
                            (:rcon-timeout default-config))
   :monitor-interval (parse-int (get-env "MONITOR_INTERVAL")
                                (:monitor-interval default-config))
   :failure-threshold (parse-int (get-env "FAILURE_THRESHOLD")
                                 (:failure-threshold default-config))
   :log-level (get-env "LOG_LEVEL" (:log-level default-config))})

(defn validate-config
  "Validate configuration. Returns config or throws on error."
  [config]
  (when (< (:rcon-port config) 1)
    (throw (ex-info "Invalid RCON port" {:port (:rcon-port config)})))
  (when (< (:monitor-interval config) 1000)
    (throw (ex-info "Monitor interval too short" {:interval (:monitor-interval config)})))
  config)
