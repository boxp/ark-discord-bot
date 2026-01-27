(ns ark-discord-bot.effects.gateway-test
    "Tests for Discord Gateway."
    (:require [ark-discord-bot.effects.gateway :as gateway]
              [ark-discord-bot.state :as state]
              [babashka.http-client.websocket :as ws]
              [clojure.test :refer [deftest is testing]]))

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

(deftest test-create-close-handler
  (testing "create-close-handler returns a function"
    (let [handler (gateway/create-close-handler)]
      (is (fn? handler))))
  (testing "close handler sets gateway running to false"
    (state/init-state! {:failure-threshold 3})
    (is (true? (state/gateway-running?)))
    (let [handler (gateway/create-close-handler)]
      (handler nil 1000 "Normal closure")
      (is (false? (state/gateway-running?))))))

(deftest test-create-error-handler
  (testing "create-error-handler returns a function"
    (let [handler (gateway/create-error-handler)]
      (is (fn? handler))))
  (testing "error handler does not throw"
    (let [handler (gateway/create-error-handler)]
      (is (nil? (handler nil (Exception. "Test error")))))))

(deftest test-opcode-name
  (testing "opcode-name returns correct names"
    (is (= "DISPATCH" (gateway/opcode-name 0)))
    (is (= "HELLO" (gateway/opcode-name 10)))
    (is (= "INVALID_SESSION" (gateway/opcode-name 9)))
    (is (= "UNKNOWN(99)" (gateway/opcode-name 99)))))

(deftest test-create-close-handler-with-reconnect
  (testing "close handler with reconnect triggers reconnect callback"
    (let [reconnect-called (atom false)
          reconnect-fn (fn [] (reset! reconnect-called true))
          handler (gateway/create-close-handler reconnect-fn)]
      (state/init-state! {:failure-threshold 3})
      (handler nil 1006 "")
      ;; Give future time to execute
      (Thread/sleep 100)
      (is (true? @reconnect-called))))
  (testing "close handler with reconnect sets gateway running to false"
    (state/init-state! {:failure-threshold 3})
    (is (true? (state/gateway-running?)))
    (let [handler (gateway/create-close-handler (fn []))]
      (handler nil 1006 "")
      (is (false? (state/gateway-running?))))))

(deftest test-create-close-handler-normal-close
  (testing "close handler with code 1000 triggers reconnect when not shutdown"
    (let [reconnect-called (atom false)
          reconnect-fn (fn [] (reset! reconnect-called true))
          handler (gateway/create-close-handler reconnect-fn)]
      (state/init-state! {:failure-threshold 3})
      (handler nil 1000 "Normal closure")
      (Thread/sleep 100)
      (is (true? @reconnect-called))))
  (testing "close handler with code 1000 does not trigger reconnect when shutdown"
    (let [reconnect-called (atom false)
          reconnect-fn (fn [] (reset! reconnect-called true))
          handler (gateway/create-close-handler reconnect-fn)]
      (state/init-state! {:failure-threshold 3})
      (state/shutdown!)
      (handler nil 1000 "Normal closure")
      (Thread/sleep 100)
      (is (false? @reconnect-called)))))

(deftest test-should-reconnect?
  (testing "should-reconnect? returns true when not shutdown"
    (state/init-state! {:failure-threshold 3})
    (is (true? (#'gateway/should-reconnect?))))
  (testing "should-reconnect? returns false when shutdown"
    (state/init-state! {:failure-threshold 3})
    (state/shutdown!)
    (is (false? (#'gateway/should-reconnect?)))))

(deftest test-calculate-backoff
  (testing "calculate-backoff returns initial delay for attempt 0"
    (is (= 1000 (gateway/calculate-backoff 0))))
  (testing "calculate-backoff doubles for each attempt"
    (is (= 2000 (gateway/calculate-backoff 1)))
    (is (= 4000 (gateway/calculate-backoff 2)))
    (is (= 8000 (gateway/calculate-backoff 3))))
  (testing "calculate-backoff caps at max delay"
    (is (= 60000 (gateway/calculate-backoff 10)))))

(deftest test-create-reconnect-fn
  (testing "create-reconnect-fn returns a function"
    (let [reconnect-fn (gateway/create-reconnect-fn
                        "token" (fn [_]) nil nil)]
      (is (fn? reconnect-fn)))))

(deftest test-start-heartbeat-sends-immediately
  (testing "start-heartbeat sends first heartbeat immediately (not after interval)"
    (let [send-times (atom [])
          start-time (atom nil)
          mock-ws-client (reify Object)
          interval-ms 5000]  ;; 5 second interval
      (state/init-state! {:failure-threshold 3})
      (reset! start-time (System/currentTimeMillis))
      ;; Mock send-json to record when it's called
      (with-redefs [gateway/send-json (fn [_ _]
                                        (swap! send-times conj
                                               (- (System/currentTimeMillis)
                                                  @start-time)))]
                   (let [_ (#'gateway/start-heartbeat mock-ws-client interval-ms)]
          ;; Wait a bit for the first heartbeat
                     (Thread/sleep 200)
          ;; Stop the loop
                     (state/set-gateway-running! false)
          ;; First heartbeat should be sent within 200ms, not after 5000ms
                     (is (seq @send-times) "At least one heartbeat should be sent")
                     (when (seq @send-times)
                       (is (< (first @send-times) 200)
                           "First heartbeat should be sent immediately (within 200ms)")))))))

(deftest test-build-heartbeat
  (testing "build-heartbeat creates correct payload"
    (let [payload (#'gateway/build-heartbeat 42)]
      (is (= 1 (:op payload)))  ;; HEARTBEAT opcode
      (is (= 42 (:d payload))))))

(deftest test-process-gateway-message-heartbeat-request
  (testing "process-gateway-message responds to server HEARTBEAT request (op=1)"
    (let [heartbeat-sent (atom false)
          heartbeat-payload (atom nil)
          mock-ws-client (reify Object)]
      (state/init-state! {:failure-threshold 3})
      (state/update-gateway-seq! 123)
      ;; Mock send-json to capture the heartbeat response
      (with-redefs [gateway/send-json (fn [_ payload]
                                        (reset! heartbeat-sent true)
                                        (reset! heartbeat-payload payload))]
        ;; Simulate receiving HEARTBEAT request from server (op=1)
                   (#'gateway/process-gateway-message
                    mock-ws-client "token" {:op 1} nil nil nil)
        ;; Verify heartbeat was sent immediately
                   (is (true? @heartbeat-sent)
                       "Should send heartbeat in response to server HEARTBEAT request")
                   (when @heartbeat-sent
                     (is (= 1 (:op @heartbeat-payload))
                         "Response should be HEARTBEAT opcode")
                     (is (= 123 (:d @heartbeat-payload))
                         "Response should include current sequence number"))))))

(deftest test-handle-hello-sends-identify-before-heartbeat
  (testing "handle-hello sends IDENTIFY before starting heartbeat loop"
    (let [send-order (atom [])
          mock-ws-client (reify Object)]
      (state/init-state! {:failure-threshold 3})
      (with-redefs [gateway/send-json (fn [_ payload]
                                        (swap! send-order conj (:op payload)))
                    ;; Mock start-heartbeat to track when it's called
                    gateway/start-heartbeat (fn [_ _]
                                              (swap! send-order conj :heartbeat-loop-started)
                                              nil)]
                   (#'gateway/handle-hello mock-ws-client "test-token" {:heartbeat_interval 5000})
        ;; IDENTIFY (op=2) should be sent before heartbeat loop starts
                   (is (= [2 :heartbeat-loop-started] @send-order)
                       "IDENTIFY should be sent before heartbeat loop starts")))))

(deftest test-process-gateway-message-reconnect
  (testing "process-gateway-message closes connection on RECONNECT opcode"
    (let [close-called (atom false)
          mock-ws-client (reify Object)]
      (state/init-state! {:failure-threshold 3})
      (with-redefs [ws/close! (fn [_] (reset! close-called true))]
        ;; Simulate receiving RECONNECT from server (op=7)
                   (#'gateway/process-gateway-message
                    mock-ws-client "token" {:op 7} nil nil nil)
        ;; Verify connection was closed
                   (is (true? @close-called)
                       "Should close WebSocket connection when receiving RECONNECT opcode")))))

(deftest test-heartbeat-stops-on-connection-id-change
  (testing "heartbeat loop stops when connection-id changes (reconnection)"
    (let [send-count (atom 0)
          mock-ws-client (reify Object)]
      (state/init-state! {:failure-threshold 3})
      (with-redefs [gateway/send-json (fn [_ _] (swap! send-count inc))]
                   (let [_ (#'gateway/start-heartbeat mock-ws-client 50)]
          ;; Wait for first heartbeat
                     (Thread/sleep 80)
                     (let [count-before @send-count]
            ;; Simulate reconnection by resetting gateway state
                       (state/reset-gateway-state!)
            ;; Wait for potential additional heartbeats
                       (Thread/sleep 150)
            ;; Old heartbeat loop should have stopped
            ;; Only 1-2 more heartbeats at most before noticing id change
                       (is (<= @send-count (+ count-before 2))
                           "Old heartbeat loop should stop after connection-id changes"))
          ;; Clean up
                     (state/set-gateway-running! false))))))

;; Run tests when loaded
(clojure.test/run-tests 'ark-discord-bot.effects.gateway-test)
