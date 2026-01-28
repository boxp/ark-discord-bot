(ns ark-discord-bot.system.gateway-state-test
    (:require [ark-discord-bot.system.gateway-state]
              [clojure.test :refer [deftest is testing]]
              [integrant.core :as ig]))

(deftest init-key-ark-gateway-state-test
  (testing ":ark/gateway-state creates initial state atom"
    (let [state-atom (ig/init-key :ark/gateway-state {})]
      (is (instance? clojure.lang.Atom state-atom))
      (is (nil? (:seq @state-atom)))
      (is (true? (:running? @state-atom)))
      (is (= 0 (:connection-id @state-atom)))
      (is (nil? (:channels @state-atom)))
      (is (nil? (:ws-client @state-atom))))))

(deftest gateway-state-is-mutable-test
  (testing "gateway-state atom can be updated"
    (let [state-atom (ig/init-key :ark/gateway-state {})]
      (swap! state-atom assoc :seq 42)
      (is (= 42 (:seq @state-atom)))
      (swap! state-atom assoc :running? false)
      (is (false? (:running? @state-atom))))))
