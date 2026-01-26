(ns ark-discord-bot.state-test
    "Tests for centralized application state management."
    (:require [ark-discord-bot.state :as state]
              [clojure.test :refer [deftest is testing]]))

(deftest test-init-state
  (testing "init-state creates initial state structure"
    (state/init-state! {:failure-threshold 3})
    (let [s @state/app-state]
      (is (map? s))
      (is (contains? s :gateway))
      (is (contains? s :monitor))
      (is (contains? s :config)))))

(deftest test-gateway-state
  (testing "gateway state is initialized correctly"
    (state/init-state! {:failure-threshold 3})
    (let [gw (state/get-gateway-state)]
      (is (nil? (:seq gw)))
      (is (true? (:running? gw)))
      (is (= "" (:msg-buffer gw))))))

(deftest test-monitor-state
  (testing "monitor state is initialized correctly"
    (state/init-state! {:failure-threshold 3})
    (let [mon (state/get-monitor-state)]
      (is (nil? (:last-status mon)))
      (is (= 0 (:failure-count mon)))
      (is (= 3 (:failure-threshold mon))))))

(deftest test-update-gateway-seq
  (testing "update-gateway-seq! updates sequence number"
    (state/init-state! {:failure-threshold 3})
    (state/update-gateway-seq! 42)
    (is (= 42 (:seq (state/get-gateway-state))))))

(deftest test-set-gateway-running
  (testing "set-gateway-running! updates running flag"
    (state/init-state! {:failure-threshold 3})
    (state/set-gateway-running! false)
    (is (false? (:running? (state/get-gateway-state))))))

(deftest test-append-to-msg-buffer
  (testing "append-to-msg-buffer! appends to buffer"
    (state/init-state! {:failure-threshold 3})
    (state/append-to-msg-buffer! "hello")
    (state/append-to-msg-buffer! " world")
    (is (= "hello world" (:msg-buffer (state/get-gateway-state))))))

(deftest test-clear-msg-buffer
  (testing "clear-msg-buffer! clears and returns buffer"
    (state/init-state! {:failure-threshold 3})
    (state/append-to-msg-buffer! "test message")
    (let [result (state/clear-msg-buffer!)]
      (is (= "test message" result))
      (is (= "" (:msg-buffer (state/get-gateway-state)))))))

(deftest test-update-monitor-state
  (testing "update-monitor-state! updates monitor state"
    (state/init-state! {:failure-threshold 3})
    (state/update-monitor-state! :running)
    (let [mon (state/get-monitor-state)]
      (is (= :running (:last-status mon)))
      (is (= 0 (:failure-count mon))))))

(deftest test-monitor-failure-count
  (testing "failure count increments on non-running status"
    (state/init-state! {:failure-threshold 3})
    (state/update-monitor-state! :error)
    (is (= 1 (:failure-count (state/get-monitor-state))))
    (state/update-monitor-state! :error)
    (is (= 2 (:failure-count (state/get-monitor-state))))
    (state/update-monitor-state! :running)
    (is (= 0 (:failure-count (state/get-monitor-state))))))

(deftest test-get-config
  (testing "get-config returns config"
    (state/init-state! {:failure-threshold 5 :rcon-timeout 10000})
    (let [cfg (state/get-config)]
      (is (= 5 (:failure-threshold cfg)))
      (is (= 10000 (:rcon-timeout cfg))))))

(deftest test-reset-gateway-state
  (testing "reset-gateway-state! resets gateway to initial values"
    (state/init-state! {:failure-threshold 3})
    ;; Modify gateway state
    (state/update-gateway-seq! 42)
    (state/set-gateway-running! false)
    (state/append-to-msg-buffer! "stale data")
    ;; Reset
    (state/reset-gateway-state!)
    ;; Verify reset
    (let [gw (state/get-gateway-state)]
      (is (nil? (:seq gw)))
      (is (true? (:running? gw)))
      (is (= "" (:msg-buffer gw))))))

;; Run tests when loaded
(clojure.test/run-tests 'ark-discord-bot.state-test)
