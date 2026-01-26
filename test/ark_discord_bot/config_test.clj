(ns ark-discord-bot.config-test
    "Tests for configuration management."
    (:require [ark-discord-bot.config :as config]
              [clojure.test :refer [deftest is testing]]))

(deftest test-get-env-with-value
  (testing "get-env returns value when set"
    ;; Use HOME as it's always set
    (is (some? (config/get-env "HOME")))))

(deftest test-get-env-with-default
  (testing "get-env returns default for missing var"
    (is (= "default" (config/get-env "NON_EXISTENT_VAR_12345" "default")))))

(deftest test-get-env-nil-for-missing
  (testing "get-env returns nil for missing var without default"
    (is (nil? (config/get-env "NON_EXISTENT_VAR_12345")))))

(deftest test-get-required-env-throws
  (testing "get-required-env throws for missing var"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Required env var not set"
                          (config/get-required-env "NON_EXISTENT_VAR_12345")))))

(deftest test-parse-int
  (testing "parse-int parses valid integers"
    (is (= 123 (config/parse-int "123")))
    (is (= -456 (config/parse-int "-456"))))

  (testing "parse-int returns default for invalid input"
    (is (= 100 (config/parse-int "abc" 100)))
    (is (= 50 (config/parse-int nil 50)))
    (is (nil? (config/parse-int "invalid")))))

(deftest test-validate-config-invalid-port
  (testing "validate-config throws for invalid port"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Invalid RCON port"
                          (config/validate-config {:rcon-port 0
                                                   :monitor-interval 60000})))))

(deftest test-validate-config-short-interval
  (testing "validate-config throws for short interval"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Monitor interval too short"
                          (config/validate-config {:rcon-port 27020
                                                   :monitor-interval 500})))))

(deftest test-validate-config-valid
  (testing "validate-config returns valid config"
    (let [config {:rcon-port 27020 :monitor-interval 60000}]
      (is (= config (config/validate-config config))))))

;; Run tests when loaded
(clojure.test/run-tests 'ark-discord-bot.config-test)
