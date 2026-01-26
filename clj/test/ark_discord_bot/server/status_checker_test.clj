(ns ark-discord-bot.server.status-checker-test
    "Tests for server status checker with 2-stage validation."
    (:require [ark-discord-bot.server.status-checker :as checker]
              [clojure.test :refer [deftest is testing]]))

(deftest test-determine-status-running
  (testing "status is running when k8s and rcon both succeed"
    (let [result (checker/determine-status
                  {:available? true :ready 1}
                  {:connected true})]
      (is (= :running (:status result))))))

(deftest test-determine-status-starting
  (testing "status is starting when k8s ok but rcon fails"
    (let [result (checker/determine-status
                  {:available? true :ready 1}
                  {:connected false :error "timeout"})]
      (is (= :starting (:status result))))))

(deftest test-determine-status-not-ready
  (testing "status is not-ready when k8s not available"
    (let [result (checker/determine-status
                  {:available? false :ready 0}
                  nil)]
      (is (= :not-ready (:status result))))))

(deftest test-determine-status-error
  (testing "status is error when k8s check throws"
    (let [result (checker/determine-status
                  {:error "connection refused"}
                  nil)]
      (is (= :error (:status result))))))

(deftest test-check-result-structure
  (testing "check result contains expected fields"
    (let [result (checker/determine-status
                  {:available? true :ready 1}
                  {:connected true :players []})]
      (is (contains? result :status))
      (is (contains? result :k8s))
      (is (contains? result :rcon)))))

(deftest test-format-status-message
  (testing "format-status-message creates readable output"
    (let [msg (checker/format-status-message
               {:status :running
                :k8s {:ready 1}
                :rcon {:connected true :players [{:name "Player1"}]}})]
      (is (string? msg))
      (is (pos? (count msg))))))

;; Run tests when loaded
(clojure.test/run-tests 'ark-discord-bot.server.status-checker-test)
