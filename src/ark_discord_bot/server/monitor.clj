(ns ark-discord-bot.server.monitor
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
    ;; Transitioning to success - immediate notification on change
    (= :running new-status)
    (not= (:last-status state) :running)
    ;; Failure state (new or continuing) - notify when threshold is hit exactly
    :else
    (= failure-count (:failure-threshold state))))

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

(defn format-notification
  "Format status change notification message."
  [current-status previous-status]
  (cond
    ;; Recovery to running
    (and (= current-status :running)
         (not= previous-status :running))
    "🟢 ARKサーバーが接続準備完了しました！ 🦕"

    ;; Starting from not-ready
    (and (= current-status :starting)
         (= previous-status :not-ready))
    "🟡 ARKサーバーポッドが稼働中、ゲームサーバー起動中..."

    ;; Degraded from running
    (and (#{:not-ready :starting} current-status)
         (= previous-status :running))
    "🟡 ARKサーバーが再起動中または準備未完了です..."

    ;; Error state
    (= current-status :error)
    "🔴 ARKサーバーでエラーが発生しました！ログを確認してください。"

    :else nil))
