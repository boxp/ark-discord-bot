(ns ark-discord-bot.effects.gateway-test
    "Tests for Discord Gateway."
    (:require [ark-discord-bot.effects.gateway :as gateway]
              [clojure.core.async :as async]
              [clojure.test :refer [deftest is testing]]))

;; Helper to create test gateway state
(defn- create-test-state []
  (atom {:seq nil :running? true :connection-id 0 :channels nil :ws-client nil}))

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
      (is (contains? channels :app-events))
      ;; Cleanup
      (async/close! (:ws-events channels))
      (async/close! (:control channels))
      (async/close! (:heartbeat channels))
      (async/close! (:app-events channels)))))

(deftest test-heartbeat-loop-stops-on-control
  (testing "heartbeat loop stops when stop command received on control channel"
    (let [heartbeat-chan (async/chan)
          control-chan (async/chan)
          sent-count (atom 0)
          mock-ws-client (reify Object)
          send-fn (fn [_ _] (swap! sent-count inc))
          state-atom (create-test-state)]
      ;; Start heartbeat loop with short interval
      (#'gateway/start-heartbeat-loop-with-state mock-ws-client 50 control-chan
                                                 send-fn state-atom)
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
          app-events-chan (async/chan 10)
          identify-sent (atom false)
          heartbeat-started (atom false)
          mock-ws-client (reify Object)
          state-atom (create-test-state)]
      (with-redefs [gateway/send-json (fn [_ payload]
                                        (when (= 2 (:op payload))
                                          (reset! identify-sent true)))
                    gateway/start-heartbeat-loop-with-state
                    (fn [_ _ _ _ _] (reset! heartbeat-started true))]
        ;; Start event loop
                   (#'gateway/start-event-loop-with-state mock-ws-client "test-token"
                                                          ws-events-chan control-chan
                                                          heartbeat-control-chan
                                                          app-events-chan state-atom)
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
      (async/close! heartbeat-control-chan)
      (async/close! app-events-chan))))

(deftest test-event-loop-processes-dispatch
  (testing "DISPATCH opcode sends event to app-events channel"
    (let [ws-events-chan (async/chan)
          control-chan (async/chan)
          heartbeat-control-chan (async/chan)
          app-events-chan (async/chan 10)
          mock-ws-client (reify Object)
          state-atom (create-test-state)]
      ;; Start event loop
      (#'gateway/start-event-loop-with-state mock-ws-client "test-token"
                                             ws-events-chan control-chan
                                             heartbeat-control-chan
                                             app-events-chan state-atom)
      ;; Send DISPATCH message
      (async/>!! ws-events-chan {:type :message
                                 :data {:op 0 :t "MESSAGE_CREATE" :s 1
                                        :d {:content "test"}}})
      ;; Wait for processing
      (Thread/sleep 50)
      ;; Verify event was sent to app-events channel
      (let [event (async/poll! app-events-chan)]
        (is (= :message (:type event))
            "Event type should be :message")
        (is (= {:content "test"} (:data event))
            "Event data should contain message content"))
      ;; Cleanup
      (async/>!! control-chan :shutdown)
      (async/close! ws-events-chan)
      (async/close! control-chan)
      (async/close! heartbeat-control-chan)
      (async/close! app-events-chan))))

(deftest test-event-loop-handles-close
  (testing "close event sets running? to false"
    (let [ws-events-chan (async/chan)
          control-chan (async/chan)
          heartbeat-control-chan (async/chan)
          app-events-chan (async/chan 10)
          mock-ws-client (reify Object)
          state-atom (create-test-state)
          channels {:ws-events ws-events-chan
                    :control control-chan
                    :heartbeat heartbeat-control-chan
                    :app-events app-events-chan}]
      (gateway/set-gateway-channels-with-state! state-atom channels)
      ;; Start event loop
      (#'gateway/start-event-loop-with-state mock-ws-client "test-token"
                                             ws-events-chan control-chan
                                             heartbeat-control-chan
                                             app-events-chan state-atom)
      ;; Send close event
      (async/>!! ws-events-chan {:type :close :code 1006 :reason ""})
      ;; Wait for processing
      (Thread/sleep 50)
      ;; Verify running? is false
      (is (false? (gateway/gateway-running-with-state? state-atom))
          "Gateway should not be running after close")
      ;; Cleanup - channels may have been replaced, close originals
      (async/close! ws-events-chan)
      (async/close! control-chan)
      (async/close! heartbeat-control-chan)
      (async/close! app-events-chan))))

(deftest test-event-loop-handles-reconnect-opcode
  (testing "RECONNECT opcode (op=7) closes connection"
    (let [ws-events-chan (async/chan)
          control-chan (async/chan)
          heartbeat-control-chan (async/chan)
          app-events-chan (async/chan 10)
          close-called (atom false)
          mock-ws-client (reify Object)
          state-atom (create-test-state)]
      (with-redefs [gateway/close-ws! (fn [_] (reset! close-called true))]
                   (#'gateway/start-event-loop-with-state mock-ws-client "test-token"
                                                          ws-events-chan control-chan
                                                          heartbeat-control-chan
                                                          app-events-chan state-atom)
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
      (async/close! heartbeat-control-chan)
      (async/close! app-events-chan))))

(deftest test-shutdown-stops-all-loops
  (testing "shutdown-with-state! closes channels and stops loops"
    (let [channels (gateway/create-gateway-channels)
          state-atom (create-test-state)]
      (gateway/set-gateway-channels-with-state! state-atom channels)
      ;; Shutdown
      (gateway/shutdown-with-state! state-atom)
      ;; Wait for channels to close
      (Thread/sleep 50)
      ;; Verify gateway is not running
      (is (false? (gateway/gateway-running-with-state? state-atom))
          "Gateway should not be running after shutdown"))))

(deftest test-reconnect-uses-backoff
  (testing "reconnect waits with exponential backoff"
    (let [wait-times (atom [])
          connect-attempts (atom 0)
          channels (gateway/create-gateway-channels)
          state-atom (create-test-state)]
      (gateway/set-gateway-channels-with-state! state-atom channels)
      (with-redefs [gateway/wait-ms (fn [ms] (swap! wait-times conj ms))
                    gateway/establish-websocket (fn [& _]
                                                  (swap! connect-attempts inc)
                                                  (when (< @connect-attempts 3)
                                                    (throw (Exception. "Test failure")))
                                                  ;; Success on 3rd attempt
                                                  :mock-ws)
                    gateway/start-event-loop-with-state (fn [& _] nil)]
        ;; Trigger reconnect (use blocking reconnect for predictable timing)
                   (#'gateway/reconnect-with-backoff-with-state "token" channels 0 state-atom)
        ;; Verify backoff delays (first 2 attempts fail, 3rd succeeds)
                   (is (= [1000 2000 4000] @wait-times)
                       "Should use exponential backoff"))
      ;; Cleanup
      (async/close! (:ws-events channels))
      (async/close! (:control channels))
      (async/close! (:heartbeat channels))
      (async/close! (:app-events channels)))))

;; Run tests when loaded
(clojure.test/run-tests 'ark-discord-bot.effects.gateway-test)
