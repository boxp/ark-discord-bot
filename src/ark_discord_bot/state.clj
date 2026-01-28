(ns ark-discord-bot.state
    "Centralized application state management.
   All mutable state is stored in a single atom as a hashmap.")

(defonce app-state (atom nil))

(defn- initial-gateway-state [] {:seq nil :running? true :connection-id 0 :channels nil})
(defn- initial-monitor-state [config]
  {:last-status nil :failure-count 0 :failure-threshold (:failure-threshold config)})
(defn- initial-system-state [] {:shutdown? false :ws-client nil :monitor-future nil})

(defn- create-initial-state
  "Create initial application state structure."
  [config]
  {:gateway (initial-gateway-state)
   :monitor (initial-monitor-state config)
   :system (initial-system-state)
   :config config})

(defn init-state!
  "Initialize application state with config."
  [config]
  (reset! app-state (create-initial-state config)))

;; Gateway state accessors

(defn get-gateway-state
  "Get gateway state."
  []
  (:gateway @app-state))

(defn update-gateway-seq!
  "Update gateway sequence number."
  [seq-num]
  (swap! app-state assoc-in [:gateway :seq] seq-num))

(defn get-gateway-seq
  "Get gateway sequence number."
  []
  (get-in @app-state [:gateway :seq]))

(defn set-gateway-running!
  "Set gateway running flag."
  [running?]
  (swap! app-state assoc-in [:gateway :running?] running?))

(defn gateway-running?
  "Check if gateway is running."
  []
  (get-in @app-state [:gateway :running?]))

(defn get-gateway-channels
  "Get gateway channels map."
  []
  (get-in @app-state [:gateway :channels]))

(defn set-gateway-channels!
  "Set gateway channels map."
  [channels]
  (swap! app-state assoc-in [:gateway :channels] channels))

(defn get-connection-id
  "Get current gateway connection ID."
  []
  (get-in @app-state [:gateway :connection-id]))

(defn reset-gateway-state!
  "Reset gateway state to initial values for reconnection.
   Increments connection-id to invalidate old heartbeat loops.
   Preserves channels."
  []
  (swap! app-state
         (fn [state]
           (let [new-id (inc (get-in state [:gateway :connection-id] 0))
                 channels (get-in state [:gateway :channels])]
             (assoc state :gateway {:seq nil
                                    :running? true
                                    :connection-id new-id
                                    :channels channels})))))

;; Monitor state accessors

(defn get-monitor-state
  "Get monitor state."
  []
  (:monitor @app-state))

(defn update-monitor-state!
  "Update monitor state with new status.
   Resets failure count on :running, increments otherwise."
  [new-status]
  (swap! app-state
         (fn [state]
           (let [is-failure? (not= :running new-status)
                 new-count (if is-failure?
                             (inc (get-in state [:monitor :failure-count]))
                             0)]
             (-> state
                 (assoc-in [:monitor :last-status] new-status)
                 (assoc-in [:monitor :failure-count] new-count))))))

(defn get-last-status
  "Get last monitored status."
  []
  (get-in @app-state [:monitor :last-status]))

(defn get-failure-count
  "Get current failure count."
  []
  (get-in @app-state [:monitor :failure-count]))

(defn get-failure-threshold
  "Get failure threshold."
  []
  (get-in @app-state [:monitor :failure-threshold]))

;; System state accessors

(defn system-shutdown?
  "Check if system is shutting down."
  []
  (get-in @app-state [:system :shutdown?]))

(defn shutdown!
  "Signal system shutdown."
  []
  (swap! app-state assoc-in [:system :shutdown?] true))

(defn get-ws-client
  "Get WebSocket client reference."
  []
  (get-in @app-state [:system :ws-client]))

(defn set-ws-client!
  "Set WebSocket client reference."
  [ws-client]
  (swap! app-state assoc-in [:system :ws-client] ws-client))

(defn get-monitor-future
  "Get monitor loop future reference."
  []
  (get-in @app-state [:system :monitor-future]))

(defn set-monitor-future!
  "Set monitor loop future reference."
  [monitor-future]
  (swap! app-state assoc-in [:system :monitor-future] monitor-future))

;; Config accessors

(defn get-config
  "Get application config."
  []
  (:config @app-state))
