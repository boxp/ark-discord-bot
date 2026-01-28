(ns ark-discord-bot.effects.gateway-test
    "Tests for Discord Gateway."
    (:require [ark-discord-bot.effects.gateway :as gateway]
              [ark-discord-bot.state :as state]
              [clojure.core.async :as async]
              [clojure.test :refer [deftest is testing]]))

;; Pure function tests (maintained)

(deftest test-parse-interaction-restart-confirm
  (testing "parse-interaction extracts restart_confirm"
    (let [data {:type 3  ;; MESSAGE_COMPONENT
                :data {:custom_id "restart_confirm"}
                :id "123"
                :token "token123"}
          result (gateway/parse-interaction data)]
      (is (= :restart-confirm (:action result)))
      (is (= "123" (:interaction-id result)))
      (is (= "token123" (:interaction-token result))))))

(deftest test-parse-interaction-restart-cancel
  (testing "parse-interaction extracts restart_cancel"
    (let [data {:type 3
                :data {:custom_id "restart_cancel"}
                :id "456"
                :token "token456"}
          result (gateway/parse-interaction data)]
      (is (= :restart-cancel (:action result)))
      (is (= "456" (:interaction-id result))))))

(deftest test-parse-interaction-unknown
  (testing "parse-interaction returns nil for unknown"
    (let [data {:type 3
                :data {:custom_id "unknown_action"}
                :id "789"
                :token "token789"}]
      (is (nil? (gateway/parse-interaction data))))))

(deftest test-parse-interaction-non-component
  (testing "parse-interaction returns nil for non-component"
    (let [data {:type 2  ;; Not MESSAGE_COMPONENT
                :data {:custom_id "restart_confirm"}
                :id "123"
                :token "token123"}]
      (is (nil? (gateway/parse-interaction data))))))

(deftest test-opcode-name
  (testing "opcode-name returns correct names"
    (is (= "DISPATCH" (gateway/opcode-name 0)))
    (is (= "HELLO" (gateway/opcode-name 10)))
    (is (= "INVALID_SESSION" (gateway/opcode-name 9)))
    (is (= "UNKNOWN(99)" (gateway/opcode-name 99)))))

(deftest test-calculate-backoff
  (testing "calculate-backoff returns initial delay for attempt 0"
    (is (= 1000 (gateway/calculate-backoff 0))))
  (testing "calculate-backoff doubles for each attempt"
    (is (= 2000 (gateway/calculate-backoff 1)))
    (is (= 4000 (gateway/calculate-backoff 2)))
    (is (= 8000 (gateway/calculate-backoff 3))))
  (testing "calculate-backoff caps at max delay"
    (is (= 60000 (gateway/calculate-backoff 10)))))

(deftest test-build-heartbeat
  (testing "build-heartbeat creates correct payload"
    (let [payload (#'gateway/build-heartbeat 42)]
      (is (= 1 (:op payload)))  ;; HEARTBEAT opcode
      (is (= 42 (:d payload))))))

;; Channel-based tests (new)

(deftest test-create-gateway-channels
  (testing "creates required channels"
    (let [channels (gateway/create-gateway-channels)]
      (is (contains? channels :ws-events))
      (is (contains? channels :control))
      (is (contains? channels :heartbeat))
      ;; Cleanup
      (async/close! (:ws-events channels))
      (async/close! (:control channels))
      (async/close! (:heartbeat channels)))))

(deftest test-heartbeat-loop-stops-on-control
  (testing "heartbeat loop stops when stop command received on control channel"
    (let [heartbeat-chan (async/chan)
          control-chan (async/chan)
          sent-count (atom 0)
          mock-ws-client (reify Object)
          send-fn (fn [_ _] (swap! sent-count inc))]
      (state/init-state! {:failure-threshold 3})
      ;; Start heartbeat loop with short interval
      (#'gateway/start-heartbeat-loop mock-ws-client 50 control-chan send-fn)
      ;; Wait for some heartbeats
      (Thread/sleep 120)
      (let [count-before @sent-count]
        ;; Send stop command
        (async/>!! control-chan :stop)
        ;; Wait and verify no more heartbeats
        (Thread/sleep 100)
        ;; Should have stopped after receiving :stop
        (is (<= @sent-count (+ count-before 1))
            "Heartbeat should stop after receiving stop command"))
      ;; Cleanup
      (async/close! heartbeat-chan)
      (async/close! control-chan))))

(deftest test-event-loop-processes-hello
  (testing "HELLO opcode triggers IDENTIFY and starts heartbeat"
    (let [ws-events-chan (async/chan)
          control-chan (async/chan)
          heartbeat-control-chan (async/chan)
          identify-sent (atom false)
          heartbeat-started (atom false)
          mock-ws-client (reify Object)]
      (state/init-state! {:failure-threshold 3})
      (with-redefs [gateway/send-json (fn [_ payload]
                                        (when (= 2 (:op payload))
                                          (reset! identify-sent true)))
                    gateway/start-heartbeat-loop (fn [_ _ _ _]
                                                   (reset! heartbeat-started true))]
        ;; Start event loop
                   (#'gateway/start-event-loop mock-ws-client "test-token"
                                               ws-events-chan control-chan heartbeat-control-chan
                                               nil nil nil)
        ;; Send HELLO message
                   (async/>!! ws-events-chan {:type :message
                                              :data {:op 10 :d {:heartbeat_interval 5000}}})
        ;; Wait for processing
                   (Thread/sleep 50)
        ;; Verify
                   (is @identify-sent "IDENTIFY should be sent on HELLO")
                   (is @heartbeat-started "Heartbeat loop should start on HELLO"))
      ;; Cleanup
      (async/>!! control-chan :shutdown)
      (async/close! ws-events-chan)
      (async/close! control-chan)
      (async/close! heartbeat-control-chan))))

(deftest test-event-loop-processes-dispatch
  (testing "DISPATCH opcode calls appropriate callback"
    (let [ws-events-chan (async/chan)
          control-chan (async/chan)
          heartbeat-control-chan (async/chan)
          message-received (atom nil)
          on-message (fn [msg] (reset! message-received msg))
          mock-ws-client (reify Object)]
      (state/init-state! {:failure-threshold 3})
      ;; Start event loop
      (#'gateway/start-event-loop mock-ws-client "test-token"
                                  ws-events-chan control-chan heartbeat-control-chan
                                  on-message nil nil)
      ;; Send DISPATCH message
      (async/>!! ws-events-chan {:type :message
                                 :data {:op 0 :t "MESSAGE_CREATE" :s 1
                                        :d {:content "test"}}})
      ;; Wait for processing
      (Thread/sleep 50)
      ;; Verify callback was called
      (is (= {:content "test"} @message-received)
          "on-message callback should be called with message data")
      ;; Cleanup
      (async/>!! control-chan :shutdown)
      (async/close! ws-events-chan)
      (async/close! control-chan)
      (async/close! heartbeat-control-chan))))

(deftest test-event-loop-handles-close
  (testing "close event triggers reconnect when not shutting down"
    (let [ws-events-chan (async/chan)
          control-chan (async/chan)
          heartbeat-control-chan (async/chan)
          reconnect-triggered (atom false)
          mock-ws-client (reify Object)]
      (state/init-state! {:failure-threshold 3})
      ;; Start event loop with custom reconnect behavior
      (with-redefs [gateway/schedule-reconnect (fn [_ _ _ _ _ _]
                                                 (reset! reconnect-triggered true))]
                   (#'gateway/start-event-loop mock-ws-client "test-token"
                                               ws-events-chan control-chan heartbeat-control-chan
                                               nil nil nil)
        ;; Send close event
                   (async/>!! ws-events-chan {:type :close :code 1006 :reason ""})
        ;; Wait for processing
                   (Thread/sleep 50)
        ;; Verify reconnect was triggered
                   (is @reconnect-triggered "Reconnect should be triggered on close"))
      ;; Cleanup
      (async/close! ws-events-chan)
      (async/close! control-chan)
      (async/close! heartbeat-control-chan))))

(deftest test-event-loop-handles-reconnect-opcode
  (testing "RECONNECT opcode (op=7) closes connection"
    (let [ws-events-chan (async/chan)
          control-chan (async/chan)
          heartbeat-control-chan (async/chan)
          close-called (atom false)
          mock-ws-client (reify Object)]
      (state/init-state! {:failure-threshold 3})
      (with-redefs [gateway/close-ws! (fn [_] (reset! close-called true))]
                   (#'gateway/start-event-loop mock-ws-client "test-token"
                                               ws-events-chan control-chan heartbeat-control-chan
                                               nil nil nil)
        ;; Send RECONNECT opcode
                   (async/>!! ws-events-chan {:type :message :data {:op 7}})
        ;; Wait for processing
                   (Thread/sleep 50)
        ;; Verify close was called
                   (is @close-called "WebSocket should be closed on RECONNECT opcode"))
      ;; Cleanup
      (async/>!! control-chan :shutdown)
      (async/close! ws-events-chan)
      (async/close! control-chan)
      (async/close! heartbeat-control-chan))))

(deftest test-shutdown-stops-all-loops
  (testing "shutdown! closes channels and stops loops"
    (let [channels (gateway/create-gateway-channels)]
      (state/init-state! {:failure-threshold 3})
      (state/set-gateway-channels! channels)
      ;; Shutdown
      (gateway/shutdown!)
      ;; Wait for channels to close
      (Thread/sleep 50)
      ;; Verify gateway is not running
      (is (false? (state/gateway-running?))
          "Gateway should not be running after shutdown"))))

(deftest test-reconnect-uses-backoff
  (testing "reconnect waits with exponential backoff"
    (let [wait-times (atom [])
          connect-attempts (atom 0)
          channels (gateway/create-gateway-channels)]
      (state/init-state! {:failure-threshold 3})
      (with-redefs [gateway/wait-ms (fn [ms] (swap! wait-times conj ms))
                    gateway/establish-websocket (fn [& _]
                                                  (swap! connect-attempts inc)
                                                  (when (< @connect-attempts 3)
                                                    (throw (Exception. "Test failure")))
                                                  ;; Success on 3rd attempt
                                                  :mock-ws)
                    gateway/start-event-loop (fn [& _] nil)]
        ;; Trigger reconnect
                   (#'gateway/reconnect-with-backoff "token" nil nil nil channels 0)
        ;; Verify backoff delays (first 2 attempts fail, 3rd succeeds)
                   (is (= [1000 2000 4000] @wait-times)
                       "Should use exponential backoff"))
      ;; Cleanup
      (async/close! (:ws-events channels))
      (async/close! (:control channels))
      (async/close! (:heartbeat channels)))))

;; Run tests when loaded
(clojure.test/run-tests 'ark-discord-bot.effects.gateway-test)
