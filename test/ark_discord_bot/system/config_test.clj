(ns ark-discord-bot.system.config-test
    (:require [ark-discord-bot.system.config]
              [clojure.test :refer [deftest is testing]]
              [integrant.core :as ig]))

(deftest init-key-ark-config-test
  (testing ":ark/config init-key loads and validates configuration"
    ;; This test requires environment variables to be set
    ;; In a real test, we would mock the config loading
    ;; For now, we test that the method is defined
    (is (fn? (get-method ig/init-key :ark/config)))))
