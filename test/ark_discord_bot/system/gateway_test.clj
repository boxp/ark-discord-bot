(ns ark-discord-bot.system.gateway-test
    (:require [ark-discord-bot.effects.gateway :as gateway]
              [clojure.test :refer [deftest is testing]]))

;; Test for gateway state helper functions
(deftest get-gateway-seq-with-state-test
  (testing "get-gateway-seq-with-state returns seq from state atom"
    (let [state-atom (atom {:seq 42 :running? true})]
      (is (= 42 (gateway/get-gateway-seq-with-state state-atom))))))

(deftest update-gateway-seq-with-state!-test
  (testing "update-gateway-seq-with-state! updates seq in state atom"
    (let [state-atom (atom {:seq nil :running? true})]
      (gateway/update-gateway-seq-with-state! state-atom 123)
      (is (= 123 (:seq @state-atom))))))

(deftest gateway-running-with-state?-test
  (testing "gateway-running-with-state? returns running? from state atom"
    (let [state-atom (atom {:seq nil :running? true})]
      (is (true? (gateway/gateway-running-with-state? state-atom)))
      (swap! state-atom assoc :running? false)
      (is (false? (gateway/gateway-running-with-state? state-atom))))))

(deftest set-gateway-running-with-state!-test
  (testing "set-gateway-running-with-state! updates running? in state atom"
    (let [state-atom (atom {:seq nil :running? true})]
      (gateway/set-gateway-running-with-state! state-atom false)
      (is (false? (:running? @state-atom))))))

(deftest get-gateway-channels-with-state-test
  (testing "get-gateway-channels-with-state returns channels from state atom"
    (let [channels {:ws-events :chan1 :control :chan2}
          state-atom (atom {:channels channels})]
      (is (= channels (gateway/get-gateway-channels-with-state state-atom))))))

(deftest set-gateway-channels-with-state!-test
  (testing "set-gateway-channels-with-state! updates channels in state atom"
    (let [state-atom (atom {:channels nil})
          new-channels {:ws-events :chan1 :control :chan2}]
      (gateway/set-gateway-channels-with-state! state-atom new-channels)
      (is (= new-channels (:channels @state-atom))))))

(deftest get-ws-client-with-state-test
  (testing "get-ws-client-with-state returns ws-client from state atom"
    (let [ws-client :mock-ws
          state-atom (atom {:ws-client ws-client})]
      (is (= ws-client (gateway/get-ws-client-with-state state-atom))))))

(deftest set-ws-client-with-state!-test
  (testing "set-ws-client-with-state! updates ws-client in state atom"
    (let [state-atom (atom {:ws-client nil})]
      (gateway/set-ws-client-with-state! state-atom :new-ws)
      (is (= :new-ws (:ws-client @state-atom))))))

(deftest reset-gateway-state-with-state!-test
  (testing "resets state, increments connection-id, clears shutdown-requested"
    (let [channels {:ws-events :chan1}
          state-atom (atom {:seq 42
                            :running? false
                            :connection-id 5
                            :channels channels
                            :shutdown-requested? true})]
      (gateway/reset-gateway-state-with-state! state-atom)
      (is (nil? (:seq @state-atom)))
      (is (true? (:running? @state-atom)))
      (is (= 6 (:connection-id @state-atom)))
      (is (= channels (:channels @state-atom)))
      (is (false? (:shutdown-requested? @state-atom))))))

(deftest system-shutdown-with-state?-test
  (testing "system-shutdown-with-state? returns inverse of running?"
    (let [state-atom (atom {:running? true})]
      (is (false? (gateway/system-shutdown-with-state? state-atom)))
      (swap! state-atom assoc :running? false)
      (is (true? (gateway/system-shutdown-with-state? state-atom))))))

(deftest shutdown-requested-with-state?-test
  (testing "shutdown-requested-with-state? returns shutdown-requested? from state"
    (let [state-atom (atom {:shutdown-requested? false})]
      (is (false? (gateway/shutdown-requested-with-state? state-atom)))
      (swap! state-atom assoc :shutdown-requested? true)
      (is (true? (gateway/shutdown-requested-with-state? state-atom))))))

(deftest set-shutdown-requested-with-state!-test
  (testing "set-shutdown-requested-with-state! updates shutdown-requested?"
    (let [state-atom (atom {:shutdown-requested? false})]
      (gateway/set-shutdown-requested-with-state! state-atom true)
      (is (true? (:shutdown-requested? @state-atom))))))
