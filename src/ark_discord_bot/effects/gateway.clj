(ns ark-discord-bot.effects.gateway
    "Discord Gateway WebSocket client for receiving events."
    (:require [ark-discord-bot.state :as state]
              [babashka.http-client.websocket :as ws]
              [cheshire.core :as json]))

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

(defn- send-json
  "Send JSON payload over WebSocket."
  [ws-client payload]
  (ws/send! ws-client (json/generate-string payload)))

(defn- connection-active?
  "Check if the connection is still active by verifying both running state
   and connection ID. Prevents old heartbeat loops from interfering."
  [conn-id]
  (and (state/gateway-running?)
       (= conn-id (state/get-connection-id))))

(defn- start-heartbeat
  "Start heartbeat loop in background.
   Sends first heartbeat immediately per Discord Gateway spec.
   Stops when connection-id changes (on reconnection)."
  [ws-client interval-ms]
  (let [conn-id (state/get-connection-id)]
    (future
     (loop []
       (when (connection-active? conn-id)
         (try
           (send-json ws-client (build-heartbeat (state/get-gateway-seq)))
           (catch Exception _ (state/set-gateway-running! false)))
         (Thread/sleep interval-ms)
         (recur))))))

(defn- handle-hello
  "Handle HELLO opcode - identify first, then start heartbeat.
   Discord requires IDENTIFY to be sent before starting heartbeat loop."
  [ws-client token data]
  (let [interval (:heartbeat_interval data)]
    (send-json ws-client (build-identify token))
    (start-heartbeat ws-client interval)))

(defn- handle-dispatch
  "Handle DISPATCH opcode - process events."
  [data event-type on-message on-interaction on-ready]
  (state/update-gateway-seq! (:s data))
  (case event-type
    "READY" (when on-ready (on-ready (:d data)))
    "MESSAGE_CREATE" (when on-message (on-message (:d data)))
    "INTERACTION_CREATE" (when on-interaction (on-interaction (:d data)))
    nil))

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

(defn- should-reconnect?
  "Check if we should attempt reconnection.
   Returns false if system is shutting down, true otherwise."
  []
  (not (state/system-shutdown?)))

(defn calculate-backoff
  "Calculate exponential backoff delay for reconnection attempt."
  [attempt]
  (min reconnect-max-delay-ms
       (* reconnect-initial-delay-ms (bit-shift-left 1 attempt))))

(defn create-close-handler
  "Create WebSocket close handler.
   Optional on-reconnect callback is called when reconnection should be attempted."
  ([]
   (create-close-handler nil))
  ([on-reconnect]
   (fn [_ws code reason]
     (state/set-gateway-running! false)
     (println (str "[info] [gateway] Connection closed: code=" code
                   ", reason=" reason))
     (when (and on-reconnect (should-reconnect?))
       (println "[info] [gateway] Scheduling reconnection...")
       (future (on-reconnect))))))

(defn create-error-handler
  "Create WebSocket error handler."
  []
  (fn [_ws error]
    (println (str "[error] [gateway] WebSocket error: "
                  (.getMessage error)))))

(declare connect-internal)

(defn- wait-before-reconnect
  "Wait with exponential backoff before reconnection attempt."
  [attempt]
  (let [delay-ms (calculate-backoff attempt)]
    (println (str "[info] [gateway] Reconnecting in " delay-ms
                  "ms (attempt " (inc attempt) ")..."))
    (Thread/sleep delay-ms)))

(defn- attempt-reconnect
  "Attempt a single reconnection. Returns :success or :failure."
  [token on-message on-interaction on-ready make-reconnect-fn]
  (println "[debug] [gateway] Attempting reconnection...")
  (try
    (let [ws-client (connect-internal token on-message on-interaction
                                      on-ready (make-reconnect-fn))]
      (state/set-ws-client! ws-client)
      (println "[info] [gateway] Reconnection initiated successfully")
      :success)
    (catch Exception e
      (println (str "[error] [gateway] Reconnect failed: " (type e)
                    " - " (.getMessage e)))
      :failure)))

(defn create-reconnect-fn
  "Create reconnect function for automatic reconnection.
   Uses loop/recur to avoid stack overflow on repeated failures."
  [token on-message on-interaction on-ready]
  (let [make-reconnect-fn #(create-reconnect-fn token on-message
                                                on-interaction on-ready)]
    (fn []
      (loop [attempt 0]
        (when-not (state/system-shutdown?)
          (wait-before-reconnect attempt)
          (let [result (attempt-reconnect token on-message on-interaction
                                          on-ready make-reconnect-fn)]
            (when (= result :failure)
              (recur (inc attempt)))))))))

(defn- handle-invalid-session
  "Handle INVALID_SESSION opcode."
  []
  (println "[error] [gateway] Invalid session - check bot token")
  (state/set-gateway-running! false))

(defn- handle-heartbeat-request
  "Handle HEARTBEAT opcode - respond immediately with heartbeat.
   Discord server may request immediate heartbeat to verify client is alive."
  [ws-client]
  (send-json ws-client (build-heartbeat (state/get-gateway-seq))))

(defn- process-gateway-message
  "Process a single gateway message by opcode."
  [ws token data on-message on-interaction on-ready]
  (let [op (:op data)
        event-type (:t data)]
    (println (str "[debug] [gateway] Received: op=" (opcode-name op)
                  (when event-type (str ", event=" event-type))))
    (cond
      (= op (:hello opcodes)) (handle-hello ws token (:d data))
      (= op (:heartbeat opcodes)) (handle-heartbeat-request ws)
      (= op (:dispatch opcodes)) (handle-dispatch data event-type on-message
                                                  on-interaction on-ready)
      (= op (:invalid-session opcodes)) (handle-invalid-session)
      (= op (:reconnect opcodes)) (println "[warn] [gateway] Server requested reconnect"))))

(defn- create-on-message-handler
  "Create the on-message handler for WebSocket."
  [token on-message on-interaction on-ready]
  (fn [ws msg last?]
    (state/append-to-msg-buffer! (str msg))
    (when last?
      (try
        (let [full-msg (state/clear-msg-buffer!)
              data (json/parse-string full-msg true)]
          (process-gateway-message ws token data on-message on-interaction on-ready))
        (catch Exception e
          (println (str "[error] [gateway] on-message error: " (.getMessage e))))))))

(defn- establish-websocket
  "Establish WebSocket connection with handlers."
  [token on-message on-interaction on-ready on-reconnect]
  (ws/websocket
   {:uri gateway-url
    :on-open (fn [_ws] (println "[info] [gateway] WebSocket connection established"))
    :on-message (create-on-message-handler token on-message on-interaction on-ready)
    :on-close (create-close-handler on-reconnect)
    :on-error (create-error-handler)}))

(defn- connect-internal
  "Internal connect with on-reconnect callback."
  [token on-message on-interaction on-ready on-reconnect]
  (println "[info] [gateway] Connecting to Discord Gateway...")
  (state/reset-gateway-state!)
  (try
    (establish-websocket token on-message on-interaction on-ready on-reconnect)
    (catch Exception e
      (println (str "[error] [gateway] Failed to connect: " (.getMessage e)))
      (throw e))))

(defn connect
  "Connect to Discord Gateway.
   on-message is called with MESSAGE_CREATE data.
   on-interaction is called with INTERACTION_CREATE data.
   on-ready is called with READY event data (optional).
   Returns the WebSocket client."
  ([token on-message]
   (connect token on-message nil nil))
  ([token on-message on-interaction]
   (connect token on-message on-interaction nil))
  ([token on-message on-interaction on-ready]
   (connect-internal token on-message on-interaction on-ready nil)))

(defn connect-with-reconnect
  "Connect to Discord Gateway with automatic reconnection on close.
   Arguments same as connect."
  ([token on-message]
   (connect-with-reconnect token on-message nil nil))
  ([token on-message on-interaction]
   (connect-with-reconnect token on-message on-interaction nil))
  ([token on-message on-interaction on-ready]
   (let [reconnect-fn (create-reconnect-fn token on-message
                                           on-interaction on-ready)]
     (connect-internal token on-message on-interaction on-ready reconnect-fn))))
