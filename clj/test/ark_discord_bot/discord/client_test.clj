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

(deftest test-build-restart-confirmation
  (testing "build-restart-confirmation creates embed with buttons"
    (let [result (discord/build-restart-confirmation)]
      ;; Check embed
      (is (str/includes? (:title (:embed result)) "再起動"))
      (is (= 0xFF9900 (:color (:embed result))))
      ;; Check components structure
      (is (= 1 (count (:components result))))
      (let [action-row (first (:components result))]
        (is (= 1 (:type action-row)))  ;; Action Row type
        (is (= 2 (count (:components action-row))))  ;; 2 buttons
        ;; Confirm button
        (let [confirm-btn (first (:components action-row))]
          (is (= 2 (:type confirm-btn)))  ;; Button type
          (is (= 4 (:style confirm-btn)))  ;; Danger style
          (is (= "restart_confirm" (:custom_id confirm-btn))))
        ;; Cancel button
        (let [cancel-btn (second (:components action-row))]
          (is (= 2 (:type cancel-btn)))
          (is (= 2 (:style cancel-btn)))  ;; Secondary style
          (is (= "restart_cancel" (:custom_id cancel-btn))))))))

(deftest test-build-interaction-response
  (testing "build-interaction-response creates correct structure"
    (let [result (discord/build-interaction-response 4 "テストメッセージ")]
      (is (= 4 (:type result)))
      (is (= "テストメッセージ" (get-in result [:data :content]))))))

(deftest test-build-interaction-update
  (testing "build-interaction-update creates update message response"
    (let [result (discord/build-interaction-update "更新メッセージ")]
      (is (= 7 (:type result)))  ;; UPDATE_MESSAGE type
      (is (= "更新メッセージ" (get-in result [:data :content]))))))

;; Run tests when loaded
(clojure.test/run-tests 'ark-discord-bot.discord.client-test)
