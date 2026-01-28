(ns ark-discord-bot.system.rcon-test
    (:require [ark-discord-bot.system.rcon]
              [clojure.test :refer [deftest is testing]]
              [integrant.core :as ig]))

(deftest init-key-ark-rcon-client-test
  (testing ":ark/rcon-client creates client with config"
    (let [config {:rcon-host "test-host"
                  :rcon-port 12345
                  :rcon-password "test-password"}
          client (ig/init-key :ark/rcon-client {:config config})]
      (is (map? client))
      (is (= "test-host" (:host client)))
      (is (= 12345 (:port client)))
      (is (= "test-password" (:password client))))))
