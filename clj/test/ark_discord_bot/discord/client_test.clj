(ns ark-discord-bot.discord.client-test
    "Tests for Discord HTTP client."
    (:require [ark-discord-bot.discord.client :as discord]
              [clojure.string :as str]
              [clojure.test :refer [deftest is testing]]))

(deftest test-create-client
  (testing "create-client returns client map"
    (let [c (discord/create-client "test-token" "123456789")]
      (is (= "test-token" (:token c)))
      (is (= "123456789" (:channel-id c))))))

(deftest test-build-embed
  (testing "build-embed creates correct structure"
    (let [embed (discord/build-embed "Title" "Desc" :info)]
      (is (= "Title" (:title embed)))
      (is (= "Desc" (:description embed)))
      (is (some? (:color embed))))))

(deftest test-embed-colors
  (testing "embed colors are correct for different types"
    (is (= 0x00FF00 (:color (discord/build-embed "T" "D" :success))))
    (is (= 0xFF0000 (:color (discord/build-embed "T" "D" :error))))
    (is (= 0xFFFF00 (:color (discord/build-embed "T" "D" :warning))))
    (is (= 0x3498DB (:color (discord/build-embed "T" "D" :info))))))

(deftest test-format-status-running
  (testing "format-status shows running correctly"
    (let [result (discord/format-status :running)]
      (is (str/includes? result "稼働中")))))

(deftest test-format-status-starting
  (testing "format-status shows starting correctly"
    (let [result (discord/format-status :starting)]
      (is (str/includes? result "起動中")))))

;; Run tests when loaded
(clojure.test/run-tests 'ark-discord-bot.discord.client-test)
