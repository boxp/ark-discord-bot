(ns ark-discord-bot.system.monitor-loop
    "Integrant component for background monitoring loop."
    (:require [ark-discord-bot.core.monitor :as monitor]
              [ark-discord-bot.core.status :as status]
              [ark-discord-bot.effects.discord :as discord]
              [ark-discord-bot.effects.kubernetes :as k8s]
              [ark-discord-bot.effects.rcon :as rcon]
              [clojure.core.async :as async :refer [go-loop alt! timeout <!!]]
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

(defn- calculate-projected-count [monitor-state new-status]
  (monitor/projected-failure-count monitor-state new-status))

(defn- notify-status-change [discord-client new-status result]
  (log :info (str "Status changed to: " new-status))
  (discord/send-status-message discord-client new-status
                               (status/format-status-message result)))

(defn- update-monitor-state! [monitor-state-atom new-status]
  (swap! monitor-state-atom monitor/update-state new-status))

(defn- execute-monitor-cycle [discord-client k8s-client rcon-client config monitor-state-atom]
  (let [result (check-status k8s-client rcon-client config)
        new-status (:status result)
        monitor-state @monitor-state-atom
        projected-count (calculate-projected-count monitor-state new-status)]
    (when (monitor/should-notify-with-debounce? monitor-state new-status projected-count)
      (notify-status-change discord-client new-status result))
    (update-monitor-state! monitor-state-atom new-status)))

(defn- handle-monitor-error [e]
  (when (not (k8s/is-transient-error? e))
    (log :error (str "Monitor error: " (.getMessage e)))))

(defn- should-continue-monitor? [alt-result shutdown-atom]
  (and (not= :control (first alt-result))
       (not @shutdown-atom)))

(defn start-monitor-loop
  "Start background monitoring loop using core.async.
   Returns the control channel for stopping the loop."
  [discord-client k8s-client rcon-client config monitor-state-atom shutdown-atom]
  (let [control-chan (async/chan 1)]
    (go-loop []
      (let [result (alt! (timeout (:monitor-interval config)) [:timeout]
                         control-chan ([v] [:control v]))]
        (when (should-continue-monitor? result shutdown-atom)
          (try
            (execute-monitor-cycle discord-client k8s-client rcon-client config monitor-state-atom)
            (catch Exception e (handle-monitor-error e)))
          (recur))))
    control-chan))

(defmethod ig/init-key :ark/monitor-loop [_ {:keys [discord-client k8s-client rcon-client
                                                    monitor-state config]}]
           (log :info "Starting monitor loop...")
           (let [shutdown-atom (atom false)
                 control-chan (start-monitor-loop discord-client k8s-client rcon-client
                                                  config monitor-state shutdown-atom)]
             {:control-chan control-chan
              :shutdown-atom shutdown-atom}))

(defmethod ig/halt-key! :ark/monitor-loop [_ {:keys [control-chan shutdown-atom]}]
           (reset! shutdown-atom true)
           (async/put! control-chan :stop)
           (async/close! control-chan))
