(ns ark-discord-bot.discord.commands
    "Discord command handlers for !ark commands."
    (:require [clojure.string :as str]))

(def ^:private command-pattern #"(?i)^!ark\s+(\w+)(?:\s+(.*))?$")

(def ^:private commands
     #{"help" "status" "players" "restart"})

(defn parse-command
  "Parse a message for !ark commands.
   Returns {:command :keyword :args [args]} or nil."
  [message]
  (when-let [[_ cmd args] (re-matches command-pattern message)]
    (let [cmd-lower (str/lower-case cmd)]
      (when (commands cmd-lower)
        {:command (keyword cmd-lower)
         :args (when args (str/split (str/trim args) #"\s+"))}))))

(defn format-help
  "Format help message."
  []
  (str "**ARK Server Bot Commands**\n\n"
       "`!ark help` - Show this help message\n"
       "`!ark status` - Check server status\n"
       "`!ark players` - List online players\n"
       "`!ark restart` - Restart the server"))

(defn format-players
  "Format player list."
  [players]
  (if (empty? players)
    "No players currently online."
    (str "**Online Players (" (count players) ")**\n"
         (->> players
              (map-indexed #(str (inc %1) ". " (:name %2)))
              (str/join "\n")))))

(defn format-restart-confirm
  "Format restart confirmation message."
  []
  "⚠️ Are you sure you want to restart the server?")

(defn format-restart-started
  "Format restart started message."
  []
  "🔄 Server restart initiated. This may take a few minutes.")
