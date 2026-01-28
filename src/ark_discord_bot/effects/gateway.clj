(ns ark-discord-bot.effects.gateway
    "Discord Gateway WebSocket client for receiving events.
   Uses core.async for event processing and control flow.

   State-based API: All functions accept an explicit state atom for
   managing gateway state, enabling proper lifecycle management with Integrant."
    (:require [babashka.http-client.websocket :as ws]
              [cheshire.core :as json]
              [clojure.core.async :as async :refer [go go-loop <! alt! timeout]]))

(def ^:private gateway-url "wss://gateway.discord.gg/?v=10&encoding=json")

(def ^:private opcodes
     {:dispatch 0
      :heartbeat 1
      :identify 2
      :resume 6
      :reconnect 7
      :invalid-session 9
      :hello 10
      :heartbeat-ack 11})

(def ^:private opcode-names
     {0 "DISPATCH"
      1 "HEARTBEAT"
      2 "IDENTIFY"
      6 "RESUME"
      7 "RECONNECT"
      9 "INVALID_SESSION"
      10 "HELLO"
      11 "HEARTBEAT_ACK"})

(defn opcode-name
  "Get human-readable name for opcode."
  [op]
  (get opcode-names op (str "UNKNOWN(" op ")")))

(defn- build-identify
  "Build identify payload."
  [token]
  {:op (:identify opcodes)
   :d {:token token
       :intents 33281  ;; GUILDS + GUILD_MESSAGES + MESSAGE_CONTENT
       :properties {:os "linux" :browser "babashka" :device "babashka"}}})

(defn- build-heartbeat
  "Build heartbeat payload."
  [seq-num]
  {:op (:heartbeat opcodes)
   :d seq-num})

(defn send-json
  "Send JSON payload over WebSocket."
  [ws-client payload]
  (ws/send! ws-client (json/generate-string payload)))

(defn close-ws!
  "Close WebSocket connection."
  [ws-client]
  (ws/close! ws-client))

(defn- build-interaction-result
  "Build interaction result map from action and data."
  [action data]
  {:action action
   :interaction-id (:id data)
   :interaction-token (:token data)})

(defn parse-interaction
  "Parse interaction data from INTERACTION_CREATE event.
   Returns {:action :restart-confirm|:restart-cancel
            :interaction-id :interaction-token} or nil."
  [data]
  (when (= 3 (:type data))  ;; MESSAGE_COMPONENT type
    (case (get-in data [:data :custom_id])
      "restart_confirm" (build-interaction-result :restart-confirm data)
      "restart_cancel" (build-interaction-result :restart-cancel data)
      nil)))

(def ^:private reconnect-initial-delay-ms 1000)
(def ^:private reconnect-max-delay-ms 60000)

(defn calculate-backoff
  "Calculate exponential backoff delay for reconnection attempt."
  [attempt]
  (min reconnect-max-delay-ms
       (* reconnect-initial-delay-ms (bit-shift-left 1 attempt))))

(defn wait-ms
  "Wait for specified milliseconds. Mockable for testing."
  [ms]
  (Thread/sleep ms))

;; Channel creation

(defn create-gateway-channels
  "Create gateway channel set.
   Returns {:ws-events <chan> :control <chan> :heartbeat <chan> :app-events <chan>}
   Control and heartbeat channels have buffer size 1 to prevent signal loss.
   app-events is where application events (message, interaction, ready) are sent."
  []
  {:ws-events (async/chan 100)
   :control (async/chan 1)
   :heartbeat (async/chan 1)
   :app-events (async/chan 100)})

;; =============================================================================
;; State accessor functions with explicit state atom
;; =============================================================================

(defn get-gateway-seq-with-state
  "Get gateway sequence number from state atom."
  [state-atom]
  (:seq @state-atom))

(defn update-gateway-seq-with-state!
  "Update gateway sequence number in state atom."
  [state-atom seq-num]
  (swap! state-atom assoc :seq seq-num))

(defn gateway-running-with-state?
  "Check if gateway is running from state atom."
  [state-atom]
  (:running? @state-atom))

(defn set-gateway-running-with-state!
  "Set gateway running flag in state atom."
  [state-atom running?]
  (swap! state-atom assoc :running? running?))

(defn get-gateway-channels-with-state
  "Get gateway channels from state atom."
  [state-atom]
  (:channels @state-atom))

(defn set-gateway-channels-with-state!
  "Set gateway channels in state atom."
  [state-atom channels]
  (swap! state-atom assoc :channels channels))

(defn get-ws-client-with-state
  "Get WebSocket client from state atom."
  [state-atom]
  (:ws-client @state-atom))

(defn set-ws-client-with-state!
  "Set WebSocket client in state atom."
  [state-atom ws-client]
  (swap! state-atom assoc :ws-client ws-client))

(defn reset-gateway-state-with-state!
  "Reset gateway state to initial values for reconnection.
   Increments connection-id to invalidate old heartbeat loops.
   Preserves channels."
  [state-atom]
  (swap! state-atom
         (fn [s]
           (let [new-id (inc (get s :connection-id 0))
                 channels (:channels s)]
             (assoc s
                    :seq nil
                    :running? true
                    :connection-id new-id
                    :channels channels)))))

(defn system-shutdown-with-state?
  "Check if system is shutting down (running? is false)."
  [state-atom]
  (not (:running? @state-atom)))

;; WebSocket handlers that push to channels

(defn- create-on-open-handler []
  (fn [_ws] (println "[info] [gateway] WebSocket connection established")))

(defn- process-complete-message [ws-events-chan msg-buffer]
  (let [full-msg @msg-buffer
        data (json/parse-string full-msg true)]
    (reset! msg-buffer "")
    (async/put! ws-events-chan {:type :message :data data})))

(defn- create-on-message-handler [ws-events-chan msg-buffer]
  (fn [_ws msg last?]
    (swap! msg-buffer str msg)
    (when last?
      (try
        (process-complete-message ws-events-chan msg-buffer)
        (catch Exception e
          (reset! msg-buffer "")
          (println (str "[error] [gateway] Parse error: " (.getMessage e))))))))

(defn- create-on-close-handler [ws-events-chan]
  (fn [_ws code reason]
    (println (str "[info] [gateway] Connection closed: code=" code ", reason=" reason))
    (async/put! ws-events-chan {:type :close :code code :reason reason})))

(defn- create-on-error-handler [ws-events-chan]
  (fn [_ws error]
    (println (str "[error] [gateway] WebSocket error: " (.getMessage error)))
    (async/put! ws-events-chan {:type :error :error error})))

(defn- create-ws-handlers [ws-events-chan msg-buffer]
  {:on-open (create-on-open-handler)
   :on-message (create-on-message-handler ws-events-chan msg-buffer)
   :on-close (create-on-close-handler ws-events-chan)
   :on-error (create-on-error-handler ws-events-chan)})

;; =============================================================================
;; Heartbeat loop
;; =============================================================================

(defn- send-heartbeat-safe-with-state [ws-client send-fn state-atom]
  (try
    (send-fn ws-client (build-heartbeat (get-gateway-seq-with-state state-atom)))
    (catch Exception e
      (println (str "[error] [gateway] Heartbeat send failed: " (.getMessage e))))))

(defn- should-continue-heartbeat-with-state? [alt-result state-atom]
  (and (not= :control (first alt-result))
       (gateway-running-with-state? state-atom)))

(defn start-heartbeat-loop-with-state
  [ws-client interval-ms control-chan send-fn state-atom]
  (go-loop []
    (send-heartbeat-safe-with-state ws-client send-fn state-atom)
    (let [result (alt! (timeout interval-ms) [:timeout nil]
                       control-chan ([v] [:control v]))]
      (when (should-continue-heartbeat-with-state? result state-atom) (recur)))))

;; =============================================================================
;; Event handlers
;; =============================================================================

(defn- handle-dispatch-with-state
  "Handle DISPATCH opcode - send events to app-events channel."
  [data event-type app-events-chan state-atom]
  (update-gateway-seq-with-state! state-atom (:s data))
  (case event-type
    "READY" (async/put! app-events-chan {:type :ready :data (:d data)})
    "MESSAGE_CREATE" (async/put! app-events-chan {:type :message :data (:d data)})
    "INTERACTION_CREATE" (async/put! app-events-chan {:type :interaction :data (:d data)})
    nil))

(defn- handle-hello-with-state
  "Handle HELLO opcode - identify and start heartbeat."
  [ws-client token data heartbeat-control-chan state-atom]
  (let [interval (:heartbeat_interval data)]
    (println (str "[debug] [gateway] HELLO received, interval=" interval "ms"))
    (send-json ws-client (build-identify token))
    (start-heartbeat-loop-with-state ws-client interval heartbeat-control-chan
                                     send-json state-atom)))

(defn- handle-invalid-session-with-state
  "Handle INVALID_SESSION opcode."
  [state-atom]
  (println "[error] [gateway] Invalid session - check bot token")
  (set-gateway-running-with-state! state-atom false))

(defn- handle-heartbeat-request-with-state
  "Handle HEARTBEAT opcode - respond immediately."
  [ws-client state-atom]
  (send-json ws-client (build-heartbeat (get-gateway-seq-with-state state-atom))))

(defn- handle-reconnect
  "Handle RECONNECT opcode - close connection."
  [ws-client]
  (println "[warn] [gateway] Server requested reconnect")
  (println "[info] [gateway] Closing connection for reconnect...")
  (close-ws! ws-client))

(defn- log-gateway-message [op event-type]
  (println (str "[debug] [gateway] Received: op=" (opcode-name op)
                (when event-type (str ", event=" event-type)))))

;; Dispatch by opcode
(defn- dispatch-by-opcode-with-state
  [ws-client token data op event-type hb-chan app-events-chan state-atom]
  (case op
    10 (handle-hello-with-state ws-client token (:d data) hb-chan state-atom)
    1 (handle-heartbeat-request-with-state ws-client state-atom)
    0 (handle-dispatch-with-state data event-type app-events-chan state-atom)
    9 (handle-invalid-session-with-state state-atom)
    7 (handle-reconnect ws-client)
    nil))

(defn- process-message-with-state
  [ws-client token data hb-chan app-events-chan state-atom]
  (let [op (:op data) event-type (:t data)]
    (log-gateway-message op event-type)
    (dispatch-by-opcode-with-state ws-client token data op event-type
                                   hb-chan app-events-chan state-atom)))

;; =============================================================================
;; Reconnect logic
;; =============================================================================

(declare start-event-loop-with-state)
(declare establish-websocket)

(defn- log-reconnect-start [delay-ms attempt]
  (println (str "[info] [gateway] Reconnecting in " delay-ms "ms (attempt "
                (inc attempt) ")...")))

(defn- create-reconnect-ws [channels]
  (let [msg-buffer (atom "")
        ws-handlers (create-ws-handlers (:ws-events channels) msg-buffer)]
    (ws/websocket {:uri gateway-url :on-open (:on-open ws-handlers)
                   :on-message (:on-message ws-handlers)
                   :on-close (:on-close ws-handlers)
                   :on-error (:on-error ws-handlers)})))

(defn- log-reconnect-failure [e]
  (println (str "[error] [gateway] Reconnect failed: " (type e) " - " (.getMessage e))))

(declare schedule-reconnect-with-state)

(defn- start-reconnected-session-with-state [ws-client token channels state-atom]
  (set-ws-client-with-state! state-atom ws-client)
  (start-event-loop-with-state ws-client token (:ws-events channels) (:control channels)
                               (:heartbeat channels) (:app-events channels) state-atom)
  (println "[info] [gateway] Reconnection initiated successfully"))

(defn- do-reconnect-attempt-with-state [token channels attempt state-atom]
  (try
    (reset-gateway-state-with-state! state-atom)
    (let [ws-client (create-reconnect-ws channels)]
      (start-reconnected-session-with-state ws-client token channels state-atom))
    (catch Exception e
      (log-reconnect-failure e)
      (schedule-reconnect-with-state token channels (inc attempt) state-atom))))

(defn schedule-reconnect-with-state [token channels attempt state-atom]
  (go
    (when (gateway-running-with-state? state-atom)
      (let [delay-ms (calculate-backoff attempt)]
        (log-reconnect-start delay-ms attempt)
        (<! (timeout delay-ms))
        (when (gateway-running-with-state? state-atom)
          (println "[debug] [gateway] Attempting reconnection...")
          (do-reconnect-attempt-with-state token channels attempt state-atom))))))

(defn- attempt-reconnect-sync-with-state [token channels state-atom]
  (try
    (reset-gateway-state-with-state! state-atom)
    (establish-websocket token channels)
    :success
    (catch Exception e (log-reconnect-failure e) :failure)))

(defn reconnect-with-backoff-with-state
  "Synchronous reconnect with exponential backoff. Used for testing."
  [token channels attempt state-atom]
  (loop [current-attempt attempt]
    (when (gateway-running-with-state? state-atom)
      (let [delay-ms (calculate-backoff current-attempt)]
        (log-reconnect-start delay-ms current-attempt)
        (wait-ms delay-ms)
        (when (gateway-running-with-state? state-atom)
          (println "[debug] [gateway] Attempting reconnection...")
          (let [result (attempt-reconnect-sync-with-state token channels state-atom)]
            (when (= result :failure) (recur (inc current-attempt)))))))))

;; =============================================================================
;; Event loop
;; =============================================================================

(defn- shutdown-event? [event ch]
  (or (nil? event)
      (and (= :control (first [event ch])) (= :shutdown (second [event ch])))))

(defn- handle-event-loop-shutdown [heartbeat-control-chan]
  (println "[info] [gateway] Event loop stopping...")
  (async/put! heartbeat-control-chan :stop))

(defn- drain-and-close-chan [chan]
  (async/close! chan)
  (loop [] (when (async/poll! chan) (recur))))

(defn- handle-close-event-with-state [heartbeat-control-chan token state-atom]
  (drain-and-close-chan heartbeat-control-chan)
  (set-gateway-running-with-state! state-atom false)
  (when (gateway-running-with-state? state-atom)
    (let [old-channels (get-gateway-channels-with-state state-atom)]
      (drain-and-close-chan (:ws-events old-channels))
      (let [new-channels (assoc (create-gateway-channels)
                                :app-events (:app-events old-channels))]
        (set-gateway-channels-with-state! state-atom new-channels)
        (schedule-reconnect-with-state token new-channels 0 state-atom)))))

;; Note: After setting running? to false, gateway-running-with-state? returns false,
;; so reconnect won't be scheduled during shutdown. This is the intended behavior.

(defn- handle-message-ws-event-with-state
  [ws-event ws-client token hb-chan app-events-chan state-atom]
  (process-message-with-state ws-client token (:data ws-event)
                              hb-chan app-events-chan state-atom)
  :continue)

(defn- handle-ws-event-with-state
  [ws-event ws-client token hb-chan app-events-chan state-atom]
  (case (:type ws-event)
    :message (handle-message-ws-event-with-state ws-event ws-client token
                                                 hb-chan app-events-chan state-atom)
    :close (do (handle-close-event-with-state hb-chan token state-atom) :stop)
    :error :continue
    :continue))

(defn- process-ws-event-with-state
  [ws-ev ws-client token hb-chan app-events state-atom]
  (handle-ws-event-with-state ws-ev ws-client token hb-chan app-events state-atom))

(defn start-event-loop-with-state
  [ws-client token ws-events-chan control-chan hb-chan app-events-chan state-atom]
  (go-loop []
    (let [[event ch] (alt! ws-events-chan ([e] [:ws-event e])
                           control-chan ([c] [:control c]))
          ws-ev (when (= :ws-event (first [event ch])) (second [event ch]))]
      (cond
        (shutdown-event? event ch) (handle-event-loop-shutdown hb-chan)
        ws-ev (when (= :continue (process-ws-event-with-state
                                  ws-ev ws-client token hb-chan app-events-chan state-atom))
                (recur))
        :else (recur)))))

;; =============================================================================
;; WebSocket establishment
;; =============================================================================

(defn establish-websocket
  "Establish WebSocket connection with handlers."
  [_token channels]
  (let [msg-buffer (atom "")
        ws-handlers (create-ws-handlers (:ws-events channels) msg-buffer)]
    (ws/websocket
     {:uri gateway-url
      :on-open (:on-open ws-handlers)
      :on-message (:on-message ws-handlers)
      :on-close (:on-close ws-handlers)
      :on-error (:on-error ws-handlers)})))

;; =============================================================================
;; Public API
;; =============================================================================

(defn- close-all-channels [channels]
  (async/close! (:ws-events channels))
  (async/close! (:control channels))
  (async/close! (:heartbeat channels))
  (async/close! (:app-events channels)))

(defn- start-gateway-session-with-state [token channels state-atom]
  (let [ws-client (establish-websocket token channels)]
    (set-ws-client-with-state! state-atom ws-client)
    (start-event-loop-with-state ws-client token (:ws-events channels) (:control channels)
                                 (:heartbeat channels) (:app-events channels) state-atom)
    channels))

(defn- handle-connect-error [e channels]
  (println (str "[error] [gateway] Failed to connect: " (.getMessage e)))
  (close-all-channels channels)
  (throw e))

(defn- connect-internal-with-state [token state-atom]
  (println "[info] [gateway] Connecting to Discord Gateway...")
  (let [channels (create-gateway-channels)]
    (set-gateway-channels-with-state! state-atom channels)
    (reset-gateway-state-with-state! state-atom)
    (try
      (start-gateway-session-with-state token channels state-atom)
      (:app-events channels)
      (catch Exception e (handle-connect-error e channels)))))

(defn connect-with-state
  "Connect to Discord Gateway using provided state atom.
   Returns app-events channel for receiving events.
   Events are maps with :type (:message, :interaction, :ready) and :data keys.

   The state atom should have the structure:
   {:seq nil :running? true :connection-id 0 :channels nil :ws-client nil}"
  [token state-atom]
  (connect-internal-with-state token state-atom))

(defn- shutdown-ws-client-with-state [state-atom]
  (when-let [ws (get-ws-client-with-state state-atom)]
    (try (close-ws! ws) (catch Exception _))))

(defn- close-channels-safely [channels]
  (when-let [c (:control channels)]
    (async/put! c :shutdown)
    (async/close! c))
  (when-let [h (:heartbeat channels)]
    (async/put! h :stop)
    (async/close! h))
  (when-let [e (:ws-events channels)]
    (async/close! e))
  (when-let [a (:app-events channels)]
    (async/close! a)))

(defn shutdown-with-state!
  "Shutdown gateway using provided state atom.
   1. Sets :running? to false
   2. Closes WebSocket connection
   3. Closes all channels"
  [state-atom]
  (println "[info] [gateway] Shutting down gateway...")
  (set-gateway-running-with-state! state-atom false)
  (shutdown-ws-client-with-state state-atom)
  (Thread/sleep 100)
  (when-let [channels (get-gateway-channels-with-state state-atom)]
    (close-channels-safely channels)))
