(ns ark-discord-bot.system.gateway-event-loop-test
    (:require [ark-discord-bot.system.gateway-event-loop :as event-loop]
              [clojure.string :as str]
              [clojure.test :refer [deftest is testing]]))

(deftest pal-update-result-message-test
  (testing "returns success message when dispatch succeeds"
    (let [msg (#'event-loop/pal-update-result-message {:success true})]
      (is (str/includes? msg "✅"))))
  (testing "returns failure message when dispatch fails with error"
    (let [msg (#'event-loop/pal-update-result-message {:error "GitHub API error: 403"})]
      (is (str/includes? msg "❌"))))
  (testing "returns failure message when github-token not configured"
    (let [msg (#'event-loop/pal-update-result-message {:error "GITHUB_TOKEN not configured"})]
      (is (str/includes? msg "❌")))))
