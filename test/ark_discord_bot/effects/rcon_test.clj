(ns ark-discord-bot.effects.rcon-test
    "Tests for RCON client."
    (:require [ark-discord-bot.effects.rcon :as rcon]
              [clojure.test :refer [deftest is testing]]))

(deftest test-create-client
  (testing "create-client returns client map"
    (let [c (rcon/create-client "localhost" 27020 "password")]
      (is (= "localhost" (:host c)))
      (is (= 27020 (:port c)))
      (is (= "password" (:password c)))
      (is (nil? (:socket c))))))

(deftest test-connected?-false-when-no-socket
  (testing "connected? returns false when no socket"
    (let [c (rcon/create-client "localhost" 27020 "password")]
      (is (false? (rcon/connected? c))))))

(deftest test-parse-listplayers-response
  (testing "parse-listplayers extracts player info"
    (let [response "0. PlayerOne, 76561198xxxxxx\n1. PlayerTwo, 76561198yyyyyy"
          players (rcon/parse-listplayers response)]
      (is (= 2 (count players)))
      (is (= "PlayerOne" (:name (first players))))
      (is (= "PlayerTwo" (:name (second players)))))))

(deftest test-parse-listplayers-empty
  (testing "parse-listplayers handles empty/no-players"
    (is (empty? (rcon/parse-listplayers "")))
    (is (empty? (rcon/parse-listplayers "No Players Connected")))))

;; Run tests when loaded
(clojure.test/run-tests 'ark-discord-bot.effects.rcon-test)
