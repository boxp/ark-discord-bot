(ns ark-discord-bot.system.monitor-state-test
    (:require [ark-discord-bot.system.monitor-state]
              [clojure.test :refer [deftest is testing]]
              [integrant.core :as ig]))

(deftest init-key-ark-monitor-state-test
  (testing ":ark/monitor-state creates initial state atom with threshold from config"
    (let [config {:failure-threshold 5}
          state-atom (ig/init-key :ark/monitor-state {:config config})]
      (is (instance? clojure.lang.Atom state-atom))
      (is (nil? (:last-status @state-atom)))
      (is (= 0 (:failure-count @state-atom)))
      (is (= 5 (:failure-threshold @state-atom))))))

(deftest monitor-state-is-mutable-test
  (testing "monitor-state atom can be updated"
    (let [config {:failure-threshold 3}
          state-atom (ig/init-key :ark/monitor-state {:config config})]
      (swap! state-atom assoc :last-status :running)
      (is (= :running (:last-status @state-atom)))
      (swap! state-atom assoc :failure-count 2)
      (is (= 2 (:failure-count @state-atom))))))
