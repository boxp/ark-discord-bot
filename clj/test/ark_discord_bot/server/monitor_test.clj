(ns ark-discord-bot.server.monitor-test
    "Tests for server monitor with debounce logic."
    (:require [ark-discord-bot.server.monitor :as monitor]
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

;; Run tests when loaded
(clojure.test/run-tests 'ark-discord-bot.server.monitor-test)
