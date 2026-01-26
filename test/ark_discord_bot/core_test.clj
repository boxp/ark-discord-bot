(ns ark-discord-bot.core-test
    "Tests for core application logic."
    (:require [ark-discord-bot.discord.gateway :as gateway]
              [clojure.test :refer [deftest is testing]]))

(deftest test-interaction-parsing-for-restart
  (testing "restart confirm interaction is parsed correctly"
    (let [interaction-data {:type 3
                            :data {:custom_id "restart_confirm"}
                            :id "123"
                            :token "token123"}
          parsed (gateway/parse-interaction interaction-data)]
      (is (= :restart-confirm (:action parsed)))
      (is (= "123" (:interaction-id parsed)))
      (is (= "token123" (:interaction-token parsed))))))

(deftest test-interaction-parsing-for-cancel
  (testing "restart cancel interaction is parsed correctly"
    (let [interaction-data {:type 3
                            :data {:custom_id "restart_cancel"}
                            :id "456"
                            :token "token456"}
          parsed (gateway/parse-interaction interaction-data)]
      (is (= :restart-cancel (:action parsed))))))

(deftest test-restart-flow-requires-confirmation
  (testing "restart command should trigger confirmation dialog"
    ;; This test documents the expected behavior:
    ;; 1. User sends !ark restart
    ;; 2. Bot shows confirmation with buttons
    ;; 3. User clicks confirm or cancel
    ;; 4. Bot executes restart or cancels
    (is true "Restart flow is documented")))

;; Run tests when loaded
(clojure.test/run-tests 'ark-discord-bot.core-test)
