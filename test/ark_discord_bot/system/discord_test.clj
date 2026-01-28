(ns ark-discord-bot.system.discord-test
    (:require [ark-discord-bot.system.discord]
              [clojure.test :refer [deftest is testing]]
              [integrant.core :as ig]))

(deftest init-key-ark-discord-client-test
  (testing ":ark/discord-client creates client with config"
    (let [config {:discord-token "test-token"
                  :discord-channel-id "test-channel"}
          client (ig/init-key :ark/discord-client {:config config})]
      (is (map? client))
      (is (= "test-token" (:token client)))
      (is (= "test-channel" (:channel-id client))))))
