(ns ark-discord-bot.core.monitor
    "Server monitor with debounce logic for notifications.
   Implements failure threshold to avoid notification spam.
   Recovery notifications are suppressed when the server was only briefly non-running.")

(defn create-state
  "Create initial monitor state."
  [failure-threshold recovery-cooldown-ms]
  {:last-status nil
   :failure-count 0
   :failure-threshold failure-threshold
   :last-running-at nil
   :recovery-cooldown-ms recovery-cooldown-ms})

(defn should-notify?
  "Check if notification should be sent (no debounce)."
  [state new-status]
  (or (nil? (:last-status state))
      (not= (:last-status state) new-status)))

(defn- recovery-cooldown-elapsed?
  "Check if enough time has passed since last running state."
  [state current-time-ms]
  (or (nil? (:last-running-at state))
      (>= (- current-time-ms (:last-running-at state))
          (:recovery-cooldown-ms state))))

(defn should-notify-with-debounce?
  "Check if notification should be sent with debounce.
   Failures wait for threshold; recovery is suppressed within cooldown window."
  [state new-status failure-count current-time-ms]
  (cond
    (nil? (:last-status state)) false
    (= :running new-status)
    (and (not= (:last-status state) :running)
         (recovery-cooldown-elapsed? state current-time-ms))
    :else
    (= failure-count (:failure-threshold state))))

(defn- next-failure-count [state new-status]
  (let [initial? (nil? (:last-status state))
        is-failure? (not= :running new-status)]
    (if (or (not is-failure?) initial?) 0 (inc (:failure-count state)))))

(defn update-state
  "Update monitor state with new status and current timestamp."
  [state new-status current-time-ms]
  (assoc state
         :last-status new-status
         :failure-count (next-failure-count state new-status)
         :last-running-at (if (= :running new-status)
                            current-time-ms
                            (:last-running-at state))))

(defn projected-failure-count
  "Calculate projected failure count for the next state.
   Returns 0 on initial check or success, incremented count on failure."
  [state new-status]
  (let [initial? (nil? (:last-status state))]
    (if (or (= :running new-status) initial?)
      0
      (inc (:failure-count state)))))

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
