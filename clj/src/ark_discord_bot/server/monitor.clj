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
   For failures, waits until threshold is reached."
  [state new-status failure-count]
  (cond
    ;; First status ever - always notify
    (nil? (:last-status state)) true
    ;; Same status - no notification
    (= (:last-status state) new-status) false
    ;; Transitioning to success - immediate notification
    (= :running new-status) true
    ;; Transitioning to failure - check threshold
    :else (>= failure-count (:failure-threshold state))))

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
