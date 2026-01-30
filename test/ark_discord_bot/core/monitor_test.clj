(ns ark-discord-bot.core.monitor-test
    "Tests for server monitor with debounce logic."
    (:require [ark-discord-bot.core.monitor :as monitor]
              [clojure.test :refer [deftest is testing]]))

(deftest test-create-state
  (testing "create-state initializes correctly"
    (let [state (monitor/create-state 3)]
      (is (nil? (:last-status state)))
      (is (= 0 (:failure-count state)))
      (is (= 3 (:failure-threshold state))))))

(deftest test-should-notify-first-check
  (testing "should-notify? returns true for first check"
    (let [state (monitor/create-state 3)]
      (is (monitor/should-notify? state :running)))))

(deftest test-should-notify-status-change
  (testing "should-notify? returns true on status change"
    (let [state (-> (monitor/create-state 3)
                    (monitor/update-state :running))]
      (is (monitor/should-notify? state :error)))))

(deftest test-should-notify-same-status
  (testing "should-notify? returns false for same status"
    (let [state (-> (monitor/create-state 3)
                    (monitor/update-state :running))]
      (is (not (monitor/should-notify? state :running))))))

(deftest test-debounce-initial-check-suppressed
  (testing "should-notify-with-debounce? returns false when last-status is nil"
    (let [state (monitor/create-state 3)]
      ;; First check with :running should not notify
      (is (not (monitor/should-notify-with-debounce? state :running 0)))
      ;; First check with :error should not notify
      (is (not (monitor/should-notify-with-debounce? state :error 1)))))
  (testing "after update-state, normal notification logic resumes"
    (let [state (-> (monitor/create-state 3)
                    (monitor/update-state :running))]
      ;; Now transitioning from :running to :error should work normally
      (is (not (monitor/should-notify-with-debounce? state :error 1)))
      (is (monitor/should-notify-with-debounce? state :error 3))))
  (testing "threshold=1: update-state does not increment failure-count on initial check"
    (let [state (-> (monitor/create-state 1)
                    (monitor/update-state :error))]
      ;; Initial check should not count as failure
      (is (= 0 (:failure-count state)))
      (is (= :error (:last-status state)))))
  (testing "threshold=1: notifies exactly once on second cycle"
    (let [state (-> (monitor/create-state 1)
                    (monitor/update-state :error))]
      ;; failure-count=0 after initial, next cycle increments to 1
      ;; which matches threshold=1 exactly
      (is (monitor/should-notify-with-debounce? state :error 1))
      ;; failure-count=2 on third cycle should NOT notify (exactly once)
      (is (not (monitor/should-notify-with-debounce? state :error 2))))))

(deftest test-debounce-failures
  (testing "debounce delays notification until threshold"
    (let [state (-> (monitor/create-state 3)
                    (monitor/update-state :running))]
      ;; First failure - no notification yet
      (is (not (monitor/should-notify-with-debounce? state :error 1)))
      ;; Second failure - still no notification
      (is (not (monitor/should-notify-with-debounce? state :error 2)))
      ;; Third failure - now notify
      (is (monitor/should-notify-with-debounce? state :error 3)))))

(deftest test-update-state
  (testing "update-state updates correctly"
    (let [state (-> (monitor/create-state 3)
                    (monitor/update-state :running))]
      (is (= :running (:last-status state)))
      (is (= 0 (:failure-count state))))))

(deftest test-reset-failure-count-on-success
  (testing "failure count resets on success"
    (let [state (-> (monitor/create-state 3)
                    (assoc :failure-count 2)
                    (monitor/update-state :running))]
      (is (= 0 (:failure-count state))))))

(deftest test-projected-failure-count
  (testing "returns 0 for running status"
    (let [state (-> (monitor/create-state 3)
                    (monitor/update-state :running))]
      (is (= 0 (monitor/projected-failure-count state :running)))))
  (testing "increments failure-count for non-running status"
    (let [state (-> (monitor/create-state 3)
                    (monitor/update-state :running))]
      (is (= 1 (monitor/projected-failure-count state :error)))))
  (testing "does not increment on initial check (last-status nil)"
    (let [state (monitor/create-state 3)]
      (is (= 0 (monitor/projected-failure-count state :error)))
      (is (= 0 (monitor/projected-failure-count state :running))))))

;; Run tests when loaded
(clojure.test/run-tests 'ark-discord-bot.core.monitor-test)
