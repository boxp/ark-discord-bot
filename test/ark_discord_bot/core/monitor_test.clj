(ns ark-discord-bot.core.monitor-test
    "Tests for server monitor with debounce logic."
    (:require [ark-discord-bot.core.monitor :as monitor]
              [clojure.test :refer [deftest is testing]]))

(def default-cooldown-ms 300000) ; 5 minutes

(deftest test-create-state
  (testing "create-state initializes correctly"
    (let [state (monitor/create-state 3 default-cooldown-ms)]
      (is (nil? (:last-status state)))
      (is (= 0 (:failure-count state)))
      (is (= 3 (:failure-threshold state)))
      (is (nil? (:last-running-at state)))
      (is (= default-cooldown-ms (:recovery-cooldown-ms state))))))

(deftest test-should-notify-first-check
  (testing "should-notify? returns true for first check"
    (let [state (monitor/create-state 3 default-cooldown-ms)]
      (is (monitor/should-notify? state :running)))))

(deftest test-should-notify-status-change
  (testing "should-notify? returns true on status change"
    (let [state (-> (monitor/create-state 3 default-cooldown-ms)
                    (monitor/update-state :running 1000))]
      (is (monitor/should-notify? state :error)))))

(deftest test-should-notify-same-status
  (testing "should-notify? returns false for same status"
    (let [state (-> (monitor/create-state 3 default-cooldown-ms)
                    (monitor/update-state :running 1000))]
      (is (not (monitor/should-notify? state :running))))))

(deftest test-debounce-initial-check-suppressed
  (testing "should-notify-with-debounce? returns false when last-status is nil"
    (let [state (monitor/create-state 3 default-cooldown-ms)]
      ;; First check with :running should not notify
      (is (not (monitor/should-notify-with-debounce? state :running 0 1000)))
      ;; First check with :error should not notify
      (is (not (monitor/should-notify-with-debounce? state :error 1 1000)))))
  (testing "after update-state, normal notification logic resumes"
    (let [state (-> (monitor/create-state 3 default-cooldown-ms)
                    (monitor/update-state :running 1000))]
      ;; Now transitioning from :running to :error should work normally
      (is (not (monitor/should-notify-with-debounce? state :error 1 2000)))
      (is (monitor/should-notify-with-debounce? state :error 3 2000))))
  (testing "threshold=1: update-state does not increment failure-count on initial check"
    (let [state (-> (monitor/create-state 1 default-cooldown-ms)
                    (monitor/update-state :error 1000))]
      ;; Initial check should not count as failure
      (is (= 0 (:failure-count state)))
      (is (= :error (:last-status state)))))
  (testing "threshold=1: notifies exactly once on second cycle"
    (let [state (-> (monitor/create-state 1 default-cooldown-ms)
                    (monitor/update-state :error 1000))]
      ;; failure-count=0 after initial, next cycle increments to 1
      ;; which matches threshold=1 exactly
      (is (monitor/should-notify-with-debounce? state :error 1 2000))
      ;; failure-count=2 on third cycle should NOT notify (exactly once)
      (is (not (monitor/should-notify-with-debounce? state :error 2 3000))))))

(deftest test-debounce-failures
  (testing "debounce delays notification until threshold"
    (let [state (-> (monitor/create-state 3 default-cooldown-ms)
                    (monitor/update-state :running 1000))]
      ;; First failure - no notification yet
      (is (not (monitor/should-notify-with-debounce? state :error 1 2000)))
      ;; Second failure - still no notification
      (is (not (monitor/should-notify-with-debounce? state :error 2 3000)))
      ;; Third failure - now notify
      (is (monitor/should-notify-with-debounce? state :error 3 4000)))))

(deftest test-update-state
  (testing "update-state updates correctly"
    (let [state (-> (monitor/create-state 3 default-cooldown-ms)
                    (monitor/update-state :running 1000))]
      (is (= :running (:last-status state)))
      (is (= 0 (:failure-count state)))
      (is (= 1000 (:last-running-at state)))))
  (testing "update-state does not change last-running-at on failure"
    (let [state (-> (monitor/create-state 3 default-cooldown-ms)
                    (monitor/update-state :running 1000)
                    (monitor/update-state :starting 2000))]
      (is (= :starting (:last-status state)))
      (is (= 1000 (:last-running-at state))))))

(deftest test-reset-failure-count-on-success
  (testing "failure count resets on success"
    (let [state (-> (monitor/create-state 3 default-cooldown-ms)
                    (assoc :failure-count 2)
                    (monitor/update-state :running 1000))]
      (is (= 0 (:failure-count state))))))

(deftest test-projected-failure-count
  (testing "returns 0 for running status"
    (let [state (-> (monitor/create-state 3 default-cooldown-ms)
                    (monitor/update-state :running 1000))]
      (is (= 0 (monitor/projected-failure-count state :running)))))
  (testing "increments failure-count for non-running status"
    (let [state (-> (monitor/create-state 3 default-cooldown-ms)
                    (monitor/update-state :running 1000))]
      (is (= 1 (monitor/projected-failure-count state :error)))))
  (testing "does not increment on initial check (last-status nil)"
    (let [state (monitor/create-state 3 default-cooldown-ms)]
      (is (= 0 (monitor/projected-failure-count state :error)))
      (is (= 0 (monitor/projected-failure-count state :running))))))

(deftest test-recovery-debounce
  (testing "recovery notification is suppressed when server was down briefly"
    (let [base-time 1000000
          state (-> (monitor/create-state 3 default-cooldown-ms)
                    (monitor/update-state :running base-time)
                    (monitor/update-state :starting (+ base-time 1000)))
          recovery-time (+ base-time 30000)]
      (is (not (monitor/should-notify-with-debounce? state :running 0 recovery-time)))))
  (testing "recovery notification is sent when server was down long enough"
    (let [base-time 1000000
          state (-> (monitor/create-state 3 default-cooldown-ms)
                    (monitor/update-state :running base-time)
                    (monitor/update-state :starting (+ base-time 1000)))
          recovery-time (+ base-time (* 6 60 1000))]
      (is (monitor/should-notify-with-debounce? state :running 0 recovery-time))))
  (testing "first recovery (last-running-at nil) always notifies"
    (let [state (-> (monitor/create-state 3 default-cooldown-ms)
                    (monitor/update-state :starting 1000))]
      ;; Never was running, then recovers - should notify
      (is (monitor/should-notify-with-debounce? state :running 0 2000))))
  (testing "recovery cooldown resets after sending notification"
    (let [base-time 1000000
          cooldown-ms 300000
          state (-> (monitor/create-state 3 cooldown-ms)
                    (monitor/update-state :running base-time)
                    (monitor/update-state :starting (+ base-time 1000)))
          recovery-time (+ base-time (* 6 60 1000))
          post-recovery-state (monitor/update-state state :running recovery-time)
          second-recovery-time (+ recovery-time 60000)]
      (is (monitor/should-notify-with-debounce? state :running 0 recovery-time))
      (is (= recovery-time (:last-running-at post-recovery-state)))
      (is (not (monitor/should-notify-with-debounce?
                (monitor/update-state post-recovery-state :starting (+ recovery-time 1000))
                :running 0 second-recovery-time))))))

;; Run tests when loaded
(clojure.test/run-tests 'ark-discord-bot.core.monitor-test)
