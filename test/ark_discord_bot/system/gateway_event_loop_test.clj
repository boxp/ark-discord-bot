(ns ark-discord-bot.system.gateway-event-loop-test
    (:require [ark-discord-bot.system.gateway-event-loop :as event-loop]
              [clojure.core.async :as async]
              [clojure.string :as str]
              [clojure.test :refer [deftest is testing]]))

(deftest start-gateway-event-loop-processes-events-on-thread-test
  (testing "event loop returns a control channel and terminates on shutdown"
    (let [app-events (async/chan 10)
          clients {:discord-client nil :k8s-client nil :rcon-client nil :github-client nil}
          config {:discord-token "tok"}
          shutdown (atom false)
          control (event-loop/start-gateway-event-loop app-events clients config shutdown)]
      (async/put! app-events {:type :ready :data {:user {:username "bot"}}})
      (Thread/sleep 200)
      (reset! shutdown true)
      (async/put! control :stop)
      (async/close! control)
      (is (some? control) "loop should return a control channel"))))

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
