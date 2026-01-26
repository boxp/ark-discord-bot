(ns ark-discord-bot.discord.gateway-test
    "Tests for Discord Gateway."
    (:require [ark-discord-bot.discord.gateway :as gateway]
              [ark-discord-bot.state :as state]
              [clojure.test :refer [deftest is testing]]))

(deftest test-parse-interaction-restart-confirm
  (testing "parse-interaction extracts restart_confirm"
    (let [data {:type 3  ;; MESSAGE_COMPONENT
                :data {:custom_id "restart_confirm"}
                :id "123"
                :token "token123"}
          result (gateway/parse-interaction data)]
      (is (= :restart-confirm (:action result)))
      (is (= "123" (:interaction-id result)))
      (is (= "token123" (:interaction-token result))))))

(deftest test-parse-interaction-restart-cancel
  (testing "parse-interaction extracts restart_cancel"
    (let [data {:type 3
                :data {:custom_id "restart_cancel"}
                :id "456"
                :token "token456"}
          result (gateway/parse-interaction data)]
      (is (= :restart-cancel (:action result)))
      (is (= "456" (:interaction-id result))))))

(deftest test-parse-interaction-unknown
  (testing "parse-interaction returns nil for unknown"
    (let [data {:type 3
                :data {:custom_id "unknown_action"}
                :id "789"
                :token "token789"}]
      (is (nil? (gateway/parse-interaction data))))))

(deftest test-parse-interaction-non-component
  (testing "parse-interaction returns nil for non-component"
    (let [data {:type 2  ;; Not MESSAGE_COMPONENT
                :data {:custom_id "restart_confirm"}
                :id "123"
                :token "token123"}]
      (is (nil? (gateway/parse-interaction data))))))

(deftest test-create-close-handler
  (testing "create-close-handler returns a function"
    (let [handler (gateway/create-close-handler)]
      (is (fn? handler))))
  (testing "close handler sets gateway running to false"
    (state/init-state! {:failure-threshold 3})
    (is (true? (state/gateway-running?)))
    (let [handler (gateway/create-close-handler)]
      (handler nil 1000 "Normal closure")
      (is (false? (state/gateway-running?))))))

(deftest test-create-error-handler
  (testing "create-error-handler returns a function"
    (let [handler (gateway/create-error-handler)]
      (is (fn? handler))))
  (testing "error handler does not throw"
    (let [handler (gateway/create-error-handler)]
      (is (nil? (handler nil (Exception. "Test error")))))))

(deftest test-opcode-name
  (testing "opcode-name returns correct names"
    (is (= "DISPATCH" (gateway/opcode-name 0)))
    (is (= "HELLO" (gateway/opcode-name 10)))
    (is (= "INVALID_SESSION" (gateway/opcode-name 9)))
    (is (= "UNKNOWN(99)" (gateway/opcode-name 99)))))

;; Run tests when loaded
(clojure.test/run-tests 'ark-discord-bot.discord.gateway-test)
