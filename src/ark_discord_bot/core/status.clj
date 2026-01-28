(ns ark-discord-bot.core.status
    "Server status checker with 2-stage validation.
   Stage 1: Kubernetes deployment check
   Stage 2: RCON connectivity validation")

(defn- compute-status
  "Compute status keyword from k8s and rcon state."
  [k8s-error? k8s-ready? rcon-ok?]
  (cond
    k8s-error? :error
    (not k8s-ready?) :not-ready
    (not rcon-ok?) :starting
    :else :running))

(defn determine-status
  "Determine server status from k8s and rcon results."
  [k8s-result rcon-result]
  {:status (compute-status (contains? k8s-result :error)
                           (:available? k8s-result)
                           (:connected rcon-result))
   :k8s k8s-result
   :rcon rcon-result})

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
