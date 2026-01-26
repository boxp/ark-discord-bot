(ns ark-discord-bot.core.commands-test
    "Tests for Discord command handlers."
    (:require [ark-discord-bot.core.commands :as commands]
              [clojure.string :as str]
              [clojure.test :refer [deftest is testing]]))

(deftest test-parse-command-help
  (testing "parse-command recognizes !ark help"
    (let [result (commands/parse-command "!ark help")]
      (is (= :help (:command result))))))

(deftest test-parse-command-status
  (testing "parse-command recognizes !ark status"
    (let [result (commands/parse-command "!ark status")]
      (is (= :status (:command result))))))

(deftest test-parse-command-players
  (testing "parse-command recognizes !ark players"
    (let [result (commands/parse-command "!ark players")]
      (is (= :players (:command result))))))

(deftest test-parse-command-restart
  (testing "parse-command recognizes !ark restart"
    (let [result (commands/parse-command "!ark restart")]
      (is (= :restart (:command result))))))

(deftest test-parse-command-unknown
  (testing "parse-command returns nil for unknown"
    (is (nil? (commands/parse-command "!ark unknown")))
    (is (nil? (commands/parse-command "hello")))))

(deftest test-parse-command-case-insensitive
  (testing "parse-command is case insensitive"
    (is (= :help (:command (commands/parse-command "!ARK HELP"))))
    (is (= :status (:command (commands/parse-command "!Ark Status"))))))

(deftest test-format-help
  (testing "format-help returns help text"
    (let [help (commands/format-help)]
      (is (str/includes? help "!ark help"))
      (is (str/includes? help "!ark status"))
      (is (str/includes? help "!ark players"))
      (is (str/includes? help "!ark restart")))))

(deftest test-format-players-with-players
  (testing "format-players lists players"
    (let [players [{:name "Player1"} {:name "Player2"}]
          result (commands/format-players players)]
      (is (str/includes? result "Player1"))
      (is (str/includes? result "Player2")))))

(deftest test-format-players-empty
  (testing "format-players shows no players"
    (let [result (commands/format-players [])]
      (is (str/includes? result "オンラインのプレイヤーはいません")))))

;; Run tests when loaded
(clojure.test/run-tests 'ark-discord-bot.core.commands-test)
