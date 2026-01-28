(ns ark-discord-bot.effects.rcon
    "RCON client for ARK server communication.
   All I/O functions return core.async channels."
    (:require [ark-discord-bot.rcon.protocol :as protocol]
              [clojure.core.async :as async]
              [clojure.string :as str])
    (:import [java.io DataInputStream DataOutputStream]
             [java.net Socket]))

(defn create-client
  "Create an RCON client configuration."
  [host port password]
  {:host host :port port :password password :socket nil})

(defn connected?
  "Check if client is connected."
  [client]
  (some? (:socket client)))

(defn- read-response
  "Read RCON response from input stream."
  [in]
  (let [size-bytes (byte-array 4)]
    (.readFully in size-bytes)
    (let [size (protocol/read-int-le size-bytes)
          response-bytes (byte-array size)]
      (.readFully in response-bytes)
      (protocol/unpack-response response-bytes))))

(defn- send-packet
  "Send packet and receive response."
  [socket request-id packet-type body]
  (let [out (DataOutputStream. (.getOutputStream socket))
        in (DataInputStream. (.getInputStream socket))
        packet (protocol/pack-packet request-id packet-type body)]
    (.write out packet)
    (.flush out)
    (read-response in)))

(defn- connect-impl
  "Connect and authenticate to RCON server (synchronous implementation)."
  [client timeout-ms]
  (let [socket (Socket. (:host client) (:port client))]
    (.setSoTimeout socket timeout-ms)
    (let [auth-resp (send-packet socket 1
                                 protocol/SERVERDATA_AUTH
                                 (:password client))]
      (if (= -1 (:id auth-resp))
        (do (.close socket)
            (throw (ex-info "RCON auth failed" {:response auth-resp})))
        (assoc client :socket socket)))))

(defn connect
  "Connect and authenticate to RCON server. Returns a channel."
  [client timeout-ms]
  (async/thread
    (connect-impl client timeout-ms)))

(defn- disconnect-impl
  "Close RCON connection (synchronous implementation)."
  [client]
  (when-let [socket (:socket client)]
    (.close socket))
  (assoc client :socket nil))

(defn disconnect
  "Close RCON connection. Returns a channel."
  [client]
  (async/thread
    (disconnect-impl client)))

(defn- execute-impl
  "Execute RCON command and return response (synchronous implementation)."
  [client command]
  (when-not (connected? client)
    (throw (ex-info "Not connected" {})))
  (let [resp (send-packet (:socket client) 2
                          protocol/SERVERDATA_EXECCOMMAND
                          command)]
    (:body resp)))

(defn execute
  "Execute RCON command and return response. Returns a channel."
  [client command]
  (async/thread
    (execute-impl client command)))

(defn- parse-player-line
  "Parse a single player line from ListPlayers output."
  [line]
  (when-let [[_ name steam-id] (re-matches #"\d+\.\s+(.+),\s+(\S+)" line)]
    {:name (str/trim name) :steam-id steam-id}))

(defn parse-listplayers
  "Parse ListPlayers response into player list."
  [response]
  (if (or (str/blank? response)
          (str/includes? response "No Players"))
    []
    (->> (str/split-lines response)
         (keep parse-player-line))))
