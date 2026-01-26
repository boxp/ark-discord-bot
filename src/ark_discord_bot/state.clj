(ns ark-discord-bot.state
    "Centralized application state management.
   All mutable state is stored in a single atom as a hashmap.")

(defonce app-state (atom nil))

(defn- create-initial-state
  "Create initial application state structure."
  [config]
  {:gateway {:seq nil
             :running? true
             :msg-buffer ""}
   :monitor {:last-status nil
             :failure-count 0
             :failure-threshold (:failure-threshold config)}
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

(defn append-to-msg-buffer!
  "Append message to gateway message buffer."
  [msg]
  (swap! app-state update-in [:gateway :msg-buffer] str msg))

(defn clear-msg-buffer!
  "Clear and return gateway message buffer."
  []
  (let [result (get-in @app-state [:gateway :msg-buffer])]
    (swap! app-state assoc-in [:gateway :msg-buffer] "")
    result))

(defn reset-gateway-state!
  "Reset gateway state to initial values for reconnection."
  []
  (swap! app-state assoc :gateway {:seq nil
                                   :running? true
                                   :msg-buffer ""}))

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

;; Config accessors

(defn get-config
  "Get application config."
  []
  (:config @app-state))
