(ns ark-discord-bot.rcon.client-test
    "Tests for RCON client."
    (:require [ark-discord-bot.rcon.client :as client]
              [clojure.test :refer [deftest is testing]]))

(deftest test-create-client
  (testing "create-client returns client map"
    (let [c (client/create-client "localhost" 27020 "password")]
      (is (= "localhost" (:host c)))
      (is (= 27020 (:port c)))
      (is (= "password" (:password c)))
      (is (nil? (:socket c))))))

(deftest test-connected?-false-when-no-socket
  (testing "connected? returns false when no socket"
    (let [c (client/create-client "localhost" 27020 "password")]
      (is (false? (client/connected? c))))))

(deftest test-parse-listplayers-response
  (testing "parse-listplayers extracts player info"
    (let [response "0. PlayerOne, 76561198xxxxxx\n1. PlayerTwo, 76561198yyyyyy"
          players (client/parse-listplayers response)]
      (is (= 2 (count players)))
      (is (= "PlayerOne" (:name (first players))))
      (is (= "PlayerTwo" (:name (second players)))))))

(deftest test-parse-listplayers-empty
  (testing "parse-listplayers handles empty/no-players"
    (is (empty? (client/parse-listplayers "")))
    (is (empty? (client/parse-listplayers "No Players Connected")))))

;; Run tests when loaded
(clojure.test/run-tests 'ark-discord-bot.rcon.client-test)
