(ns ark-discord-bot.effects.gateway
    "Discord Gateway WebSocket client for receiving events.
   Uses core.async for event processing and control flow."
    (:require [ark-discord-bot.state :as state]
              [babashka.http-client.websocket :as ws]
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
   Returns {:ws-events <chan> :control <chan> :heartbeat <chan>}"
  []
  {:ws-events (async/chan 100)
   :control (async/chan)
   :heartbeat (async/chan)})

;; WebSocket handlers that push to channels

(defn- create-ws-handlers
  "Create WebSocket handlers that push events to ws-events channel."
  [ws-events-chan msg-buffer]
  {:on-open (fn [_ws]
              (println "[info] [gateway] WebSocket connection established"))
   :on-message (fn [_ws msg last?]
                 (swap! msg-buffer str msg)
                 (when last?
                   (try
                     (let [full-msg @msg-buffer
                           data (json/parse-string full-msg true)]
                       (reset! msg-buffer "")
                       (async/put! ws-events-chan {:type :message :data data}))
                     (catch Exception e
                       (reset! msg-buffer "")
                       (println (str "[error] [gateway] Parse error: " (.getMessage e)))))))
   :on-close (fn [_ws code reason]
               (println (str "[info] [gateway] Connection closed: code=" code
                             ", reason=" reason))
               (async/put! ws-events-chan {:type :close :code code :reason reason}))
   :on-error (fn [_ws error]
               (println (str "[error] [gateway] WebSocket error: "
                             (.getMessage error)))
               (async/put! ws-events-chan {:type :error :error error}))})

;; Heartbeat loop

(defn start-heartbeat-loop
  "Start heartbeat loop using go-loop and alt!.
   Sends heartbeat every interval-ms, stops on control channel message."
  [ws-client interval-ms control-chan send-fn]
  (go-loop []
    ;; Send heartbeat immediately on first iteration
    (try
      (send-fn ws-client (build-heartbeat (state/get-gateway-seq)))
      (catch Exception e
        (println (str "[error] [gateway] Heartbeat send failed: " (.getMessage e)))))
    ;; Wait for interval or control message
    (let [[v ch] (alt!
                  (timeout interval-ms) [:timeout nil]
                  control-chan ([v] [:control v]))]
      (when-not (= :control (first [v ch]))
        (when (and (state/gateway-running?)
                   (not (state/system-shutdown?)))
          (recur))))))

;; Event handlers

(defn- handle-dispatch
  "Handle DISPATCH opcode - process events."
  [data event-type on-message on-interaction on-ready]
  (state/update-gateway-seq! (:s data))
  (case event-type
    "READY" (when on-ready (on-ready (:d data)))
    "MESSAGE_CREATE" (when on-message (on-message (:d data)))
    "INTERACTION_CREATE" (when on-interaction (on-interaction (:d data)))
    nil))

(defn- handle-hello
  "Handle HELLO opcode - identify and start heartbeat."
  [ws-client token data heartbeat-control-chan]
  (let [interval (:heartbeat_interval data)]
    (println (str "[debug] [gateway] HELLO received, interval=" interval "ms"))
    (send-json ws-client (build-identify token))
    (start-heartbeat-loop ws-client interval heartbeat-control-chan send-json)))

(defn- handle-invalid-session
  "Handle INVALID_SESSION opcode."
  []
  (println "[error] [gateway] Invalid session - check bot token")
  (state/set-gateway-running! false))

(defn- handle-heartbeat-request
  "Handle HEARTBEAT opcode - respond immediately."
  [ws-client]
  (send-json ws-client (build-heartbeat (state/get-gateway-seq))))

(defn- handle-reconnect
  "Handle RECONNECT opcode - close connection."
  [ws-client]
  (println "[warn] [gateway] Server requested reconnect")
  (println "[info] [gateway] Closing connection for reconnect...")
  (close-ws! ws-client))

(defn- process-message
  "Process a gateway message by opcode."
  [ws-client token data on-message on-interaction on-ready heartbeat-control-chan]
  (let [op (:op data)
        event-type (:t data)]
    (println (str "[debug] [gateway] Received: op=" (opcode-name op)
                  (when event-type (str ", event=" event-type))))
    (cond
      (= op (:hello opcodes))
      (handle-hello ws-client token (:d data) heartbeat-control-chan)

      (= op (:heartbeat opcodes))
      (handle-heartbeat-request ws-client)

      (= op (:dispatch opcodes))
      (handle-dispatch data event-type on-message on-interaction on-ready)

      (= op (:invalid-session opcodes))
      (handle-invalid-session)

      (= op (:reconnect opcodes))
      (handle-reconnect ws-client))))

;; Reconnect logic

(declare start-event-loop)
(declare establish-websocket)

(defn schedule-reconnect
  "Schedule a reconnection attempt with backoff."
  [token on-message on-interaction on-ready channels attempt]
  (go
    (when-not (state/system-shutdown?)
      (let [delay-ms (calculate-backoff attempt)]
        (println (str "[info] [gateway] Reconnecting in " delay-ms
                      "ms (attempt " (inc attempt) ")..."))
        (<! (timeout delay-ms))
        (when-not (state/system-shutdown?)
          (println "[debug] [gateway] Attempting reconnection...")
          (try
            (state/reset-gateway-state!)
            (let [msg-buffer (atom "")
                  ws-handlers (create-ws-handlers (:ws-events channels) msg-buffer)
                  ws-client (ws/websocket
                             {:uri gateway-url
                              :on-open (:on-open ws-handlers)
                              :on-message (:on-message ws-handlers)
                              :on-close (:on-close ws-handlers)
                              :on-error (:on-error ws-handlers)})]
              (state/set-ws-client! ws-client)
              (start-event-loop ws-client token
                                (:ws-events channels) (:control channels)
                                (:heartbeat channels)
                                on-message on-interaction on-ready)
              (println "[info] [gateway] Reconnection initiated successfully"))
            (catch Exception e
              (println (str "[error] [gateway] Reconnect failed: " (type e)
                            " - " (.getMessage e)))
              (schedule-reconnect token on-message on-interaction
                                  on-ready channels (inc attempt)))))))))

(defn reconnect-with-backoff
  "Attempt reconnection with exponential backoff. Blocking version for testing."
  [token on-message on-interaction on-ready channels attempt]
  (loop [current-attempt attempt]
    (when-not (state/system-shutdown?)
      (let [delay-ms (calculate-backoff current-attempt)]
        (println (str "[info] [gateway] Reconnecting in " delay-ms
                      "ms (attempt " (inc current-attempt) ")..."))
        (wait-ms delay-ms)
        (when-not (state/system-shutdown?)
          (println "[debug] [gateway] Attempting reconnection...")
          (let [result (try
                         (state/reset-gateway-state!)
                         (establish-websocket token on-message on-interaction
                                              on-ready channels)
                         :success
                         (catch Exception e
                           (println (str "[error] [gateway] Reconnect failed: "
                                         (type e) " - " (.getMessage e)))
                           :failure))]
            (when (= result :failure)
              (recur (inc current-attempt)))))))))

;; Event loop

(defn start-event-loop
  "Start main event processing loop.
   Processes WebSocket events from ws-events-chan.
   Responds to control commands from control-chan."
  [ws-client token ws-events-chan control-chan heartbeat-control-chan
   on-message on-interaction on-ready]
  (go-loop []
    (let [[event ch] (alt!
                      ws-events-chan ([e] [:ws-event e])
                      control-chan ([c] [:control c]))]
      (cond
        ;; Control channel closed or shutdown command
        (or (nil? event)
            (and (= :control (first [event ch]))
                 (= :shutdown (second [event ch]))))
        (do
         (println "[info] [gateway] Event loop stopping...")
         (async/>! heartbeat-control-chan :stop))

        ;; WebSocket event
        (= :ws-event (first [event ch]))
        (let [ws-event (second [event ch])]
          (case (:type ws-event)
            :message
            (do
             (process-message ws-client token (:data ws-event)
                              on-message on-interaction on-ready
                              heartbeat-control-chan)
             (recur))

            :close
            (do
              ;; Stop heartbeat
             (async/put! heartbeat-control-chan :stop)
             (state/set-gateway-running! false)
              ;; Schedule reconnect if not shutting down
             (when-not (state/system-shutdown?)
               (let [channels (state/get-gateway-channels)]
                 (schedule-reconnect token on-message on-interaction
                                     on-ready channels 0)))
              ;; Exit loop on close - reconnect will start new loop
             nil)

            :error
            (recur)

            ;; Default case for unknown types
            (recur)))

        ;; Control message received (not shutdown)
        (= :control (first [event ch]))
        (recur)

        :else
        (recur)))))

;; WebSocket establishment

(defn establish-websocket
  "Establish WebSocket connection with handlers."
  [_token _on-message _on-interaction _on-ready ^:unused channels]
  (let [msg-buffer (atom "")
        ws-handlers (create-ws-handlers (:ws-events channels) msg-buffer)]
    (ws/websocket
     {:uri gateway-url
      :on-open (:on-open ws-handlers)
      :on-message (:on-message ws-handlers)
      :on-close (:on-close ws-handlers)
      :on-error (:on-error ws-handlers)})))

;; Public API

(defn connect
  "Connect to Discord Gateway.
   on-message is called with MESSAGE_CREATE data.
   on-interaction is called with INTERACTION_CREATE data.
   on-ready is called with READY event data (optional).
   Returns channel map for control."
  ([token on-message]
   (connect token on-message nil nil))
  ([token on-message on-interaction]
   (connect token on-message on-interaction nil))
  ([token on-message on-interaction on-ready]
   (println "[info] [gateway] Connecting to Discord Gateway...")
   (let [channels (create-gateway-channels)]
     (state/set-gateway-channels! channels)
     (state/reset-gateway-state!)
     (try
       (let [ws-client (establish-websocket token on-message on-interaction
                                            on-ready channels)]
         (state/set-ws-client! ws-client)
         (start-event-loop ws-client token
                           (:ws-events channels) (:control channels)
                           (:heartbeat channels)
                           on-message on-interaction on-ready)
         channels)
       (catch Exception e
         (println (str "[error] [gateway] Failed to connect: " (.getMessage e)))
         (async/close! (:ws-events channels))
         (async/close! (:control channels))
         (async/close! (:heartbeat channels))
         (throw e))))))

(defn shutdown!
  "Shutdown gateway connection and all loops."
  []
  (println "[info] [gateway] Shutting down gateway...")
  (when-let [channels (state/get-gateway-channels)]
    ;; Send shutdown to event loop
    (when-let [control-chan (:control channels)]
      (async/put! control-chan :shutdown)
      (async/close! control-chan))
    ;; Stop heartbeat
    (when-let [heartbeat-chan (:heartbeat channels)]
      (async/put! heartbeat-chan :stop)
      (async/close! heartbeat-chan))
    ;; Close WebSocket
    (when-let [ws (state/get-ws-client)]
      (try
        (close-ws! ws)
        (catch Exception _)))
    ;; Close events channel
    (when-let [ws-events (:ws-events channels)]
      (async/close! ws-events)))
  (state/set-gateway-running! false))
