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
  [ws-client interval-ms seq-atom]
  (future
   (loop []
     (Thread/sleep interval-ms)
     (when (ws/open? ws-client)
       (send-json ws-client (build-heartbeat @seq-atom))
       (recur)))))

(defn- handle-hello
  "Handle HELLO opcode - start heartbeat and identify."
  [ws-client token data seq-atom]
  (let [interval (:heartbeat_interval data)]
    (start-heartbeat ws-client interval seq-atom)
    (send-json ws-client (build-identify token))))

(defn- handle-dispatch
  "Handle DISPATCH opcode - process events."
  [data event-type on-message seq-atom]
  (reset! seq-atom (:s data))
  (when (and (= event-type "MESSAGE_CREATE") on-message)
    (on-message (:d data))))

(defn connect
  "Connect to Discord Gateway.
   on-message is called with message data when MESSAGE_CREATE received."
  [token on-message]
  (let [seq-atom (atom nil)]
    (ws/websocket
     {:uri gateway-url
      :on-message
      (fn [_ws msg]
        (let [data (json/parse-string msg true)
              op (:op data)
              event-type (:t data)]
          (cond
            (= op (:hello opcodes))
            (handle-hello _ws token (:d data) seq-atom)

            (= op (:dispatch opcodes))
            (handle-dispatch data event-type on-message seq-atom))))})))
