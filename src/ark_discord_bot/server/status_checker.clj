(ns ark-discord-bot.server.status-checker
    "Server status checker with 2-stage validation.
   Stage 1: Kubernetes deployment check
   Stage 2: RCON connectivity validation")

(defn determine-status
  "Determine server status from k8s and rcon results."
  [k8s-result rcon-result]
  (let [k8s-error? (contains? k8s-result :error)
        k8s-ready? (:available? k8s-result)
        rcon-ok? (:connected rcon-result)
        status (cond
                 k8s-error? :error
                 (not k8s-ready?) :not-ready
                 (not rcon-ok?) :starting
                 :else :running)]
    {:status status
     :k8s k8s-result
     :rcon rcon-result}))

(defn- format-k8s-info
  "Format Kubernetes info for display."
  [k8s]
  (if (:error k8s)
    (str "K8s: エラー - " (:error k8s))
    (str "ポッド: " (:ready k8s 0) " 準備完了")))

(defn- format-rcon-info
  "Format RCON info for display."
  [rcon]
  (if (:connected rcon)
    (let [players (:players rcon [])]
      (if (empty? players)
        "プレイヤー: オンラインなし"
        (str "プレイヤー: " (count players) "人がオンライン")))
    "RCON: 未接続"))

(defn format-status-message
  "Format complete status message for display."
  [result]
  (str (format-k8s-info (:k8s result))
       "\n"
       (format-rcon-info (:rcon result))))
