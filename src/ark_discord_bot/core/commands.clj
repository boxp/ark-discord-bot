(ns ark-discord-bot.core.commands
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
  (str "**🦕 ARKサーバー管理コマンド**\n\n"
       "`!ark help` - このヘルプメッセージを表示\n"
       "`!ark status` - 現在のサーバーステータスを確認\n"
       "`!ark restart` - ARKサーバーを再起動\n"
       "`!ark players` - 現在オンラインのプレイヤー一覧を表示\n\n"
       "**📋 使用例:**\n"
       "• `!ark status` - サーバーが稼働中か確認\n"
       "• `!ark players` - オンラインプレイヤーを確認\n"
       "• `!ark restart` - サーバーを再起動（注意して使用）\n\n"
       "**ℹ️ 注意:** サーバー再起動は完了まで数分かかる場合があります。"))

(defn format-players
  "Format player list."
  [players]
  (if (empty? players)
    "🏝️ 現在オンラインのプレイヤーはいません。"
    (str "👥 **現在" (count players) "人のプレイヤーがオンライン:**\n"
         (->> players
              (map #(str "• " (:name %)))
              (str/join "\n")))))

(defn format-restart-confirm
  "Format restart confirmation message."
  []
  (str "⚠️ **ARKサーバー再起動の確認**\n\n"
       "本当にARKサーバーを再起動しますか？\n\n"
       "⚠️ **注意**: 再起動中はプレイヤーが切断され、"
       "サーバーが再度利用可能になるまで数分かかります。"))

(defn format-restart-started
  "Format restart started message."
  []
  "🔄 ARKサーバーの再起動を開始しています...")

(defn format-restart-success
  "Format restart success message."
  []
  "✅ ARKサーバーの再起動を開始しました！サーバーがオンラインに戻るまでしばらくお待ちください。")

(defn format-restart-failed
  "Format restart failed message."
  []
  "❌ ARKサーバーの再起動に失敗しました。ログを確認するか管理者にお問い合わせください。")

(defn format-players-error
  "Format players command error message."
  []
  "❌ プレイヤー情報の取得に失敗しました。サーバーがオフラインかRCONが利用できない可能性があります。")

(defn format-status-error
  "Format status command error message."
  []
  "❌ サーバーステータスの取得に失敗しました。")

(defn format-command-error
  "Format generic command error message."
  []
  "❌ コマンド処理中にエラーが発生しました。")
