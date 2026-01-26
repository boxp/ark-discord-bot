(ns ark-discord-bot.discord.gateway
    "Discord Gateway WebSocket client for receiving events."
    (:require [babashka.http-client.websocket :as ws]
              [cheshire.core :as json]))

(def ^:private gateway-url "wss://gateway.discord.gg/?v=10&encoding=json")

(def ^:private opcodes
     {:dispatch 0
      :heartbeat 1
      :identify 2
      :resume 6
      :reconnect 7
      :hello 10
      :heartbeat-ack 11})

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

(defn- start-heartbeat
  "Start heartbeat loop in background."
  [ws-client interval-ms seq-atom running-atom]
  (future
   (loop []
     (Thread/sleep interval-ms)
     (when @running-atom
       (try
         (send-json ws-client (build-heartbeat @seq-atom))
         (catch Exception _ (reset! running-atom false)))
       (recur)))))

(defn- handle-hello
  "Handle HELLO opcode - start heartbeat and identify."
  [ws-client token data seq-atom running-atom]
  (let [interval (:heartbeat_interval data)]
    (start-heartbeat ws-client interval seq-atom running-atom)
    (send-json ws-client (build-identify token))))

(defn- handle-dispatch
  "Handle DISPATCH opcode - process events."
  [data event-type on-message on-interaction on-ready seq-atom]
  (reset! seq-atom (:s data))
  (case event-type
    "READY" (when on-ready (on-ready (:d data)))
    "MESSAGE_CREATE" (when on-message (on-message (:d data)))
    "INTERACTION_CREATE" (when on-interaction (on-interaction (:d data)))
    nil))

(defn parse-interaction
  "Parse interaction data from INTERACTION_CREATE event.
   Returns {:action :restart-confirm|:restart-cancel
            :interaction-id :interaction-token} or nil."
  [data]
  (when (= 3 (:type data))  ;; MESSAGE_COMPONENT type
    (let [custom-id (get-in data [:data :custom_id])]
      (case custom-id
        "restart_confirm" {:action :restart-confirm
                           :interaction-id (:id data)
                           :interaction-token (:token data)}
        "restart_cancel" {:action :restart-cancel
                          :interaction-id (:id data)
                          :interaction-token (:token data)}
        nil))))

(defn create-close-handler
  "Create WebSocket close handler."
  [running-atom]
  (fn [_ws code reason]
    (reset! running-atom false)
    (println (str "[info] [gateway] Connection closed: code=" code
                  ", reason=" reason))))

(defn create-error-handler
  "Create WebSocket error handler."
  []
  (fn [_ws error]
    (println (str "[error] [gateway] WebSocket error: "
                  (.getMessage error)))))

(defn connect
  "Connect to Discord Gateway.
   on-message is called with MESSAGE_CREATE data.
   on-interaction is called with INTERACTION_CREATE data.
   on-ready is called with READY event data (optional)."
  ([token on-message]
   (connect token on-message nil nil))
  ([token on-message on-interaction]
   (connect token on-message on-interaction nil))
  ([token on-message on-interaction on-ready]
   (let [seq-atom (atom nil)
         running-atom (atom true)]
     (println "[info] [gateway] Connecting to Discord Gateway...")
     (try
       (ws/websocket
        {:uri gateway-url
         :on-open
         (fn [_ws]
           (println "[info] [gateway] WebSocket connection established"))
         :on-message
         (fn [_ws msg]
           (let [data (json/parse-string msg true)
                 op (:op data)
                 event-type (:t data)]
             (cond
               (= op (:hello opcodes))
               (handle-hello _ws token (:d data) seq-atom running-atom)

               (= op (:dispatch opcodes))
               (handle-dispatch data event-type on-message
                                on-interaction on-ready seq-atom))))
         :on-close (create-close-handler running-atom)
         :on-error (create-error-handler)})
       (catch Exception e
         (println (str "[error] [gateway] Failed to connect: "
                       (.getMessage e)))
         (throw e))))))
