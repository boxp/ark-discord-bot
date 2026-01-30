(ns ark-discord-bot.core.monitor
    "Server monitor with debounce logic for notifications.
   Implements failure threshold to avoid notification spam.")

(defn create-state
  "Create initial monitor state."
  [failure-threshold]
  {:last-status nil
   :failure-count 0
   :failure-threshold failure-threshold})

(defn should-notify?
  "Check if notification should be sent (no debounce)."
  [state new-status]
  (or (nil? (:last-status state))
      (not= (:last-status state) new-status)))

(defn should-notify-with-debounce?
  "Check if notification should be sent with debounce.
   For failures, waits until threshold is reached exactly once."
  [state new-status failure-count]
  (cond
    ;; Initial check (bot just started) - suppress notification
    (nil? (:last-status state))
    false
    ;; Transitioning to success - immediate notification on change
    (= :running new-status)
    (not= (:last-status state) :running)
    ;; Failure state (new or continuing) - notify when threshold is hit exactly
    :else
    (>= failure-count (:failure-threshold state))))

(defn update-state
  "Update monitor state with new status."
  [state new-status]
  (let [is-failure? (not= :running new-status)
        new-count (if is-failure?
                    (inc (:failure-count state))
                    0)]
    (assoc state
           :last-status new-status
           :failure-count new-count)))

(defn increment-failure
  "Increment failure count."
  [state]
  (update state :failure-count inc))

(defn reset-failures
  "Reset failure count to zero."
  [state]
  (assoc state :failure-count 0))

(defn- recovery-notification?
  "Check if this is a recovery to running state."
  [current previous]
  (and (= current :running)
       (not= previous :running)))

(defn- starting-notification?
  "Check if server is transitioning to starting state."
  [current previous]
  (and (= current :starting)
       (= previous :not-ready)))

(defn- degraded-notification?
  "Check if server degraded from running state."
  [current previous]
  (and (#{:not-ready :starting} current)
       (= previous :running)))

(defn format-notification
  "Format status change notification message."
  [current-status previous-status]
  (cond
    (recovery-notification? current-status previous-status)
    "🟢 ARKサーバーが接続準備完了しました！ 🦕"
    (starting-notification? current-status previous-status)
    "🟡 ARKサーバーポッドが稼働中、ゲームサーバー起動中..."
    (degraded-notification? current-status previous-status)
    "🟡 ARKサーバーが再起動中または準備未完了です..."
    (= current-status :error)
    "🔴 ARKサーバーでエラーが発生しました！ログを確認してください。"
    :else nil))
