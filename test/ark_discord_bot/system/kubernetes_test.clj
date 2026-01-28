(ns ark-discord-bot.system.kubernetes-test
    (:require [ark-discord-bot.system.kubernetes]
              [clojure.test :refer [deftest is testing]]
              [integrant.core :as ig]))

(deftest init-key-ark-k8s-client-test
  (testing ":ark/k8s-client creates client with config"
    (let [config {:k8s-namespace "test-ns"
                  :k8s-deployment "test-deployment"
                  :k8s-service "test-service"}
          client (ig/init-key :ark/k8s-client {:config config})]
      (is (map? client))
      (is (= "test-ns" (:namespace client)))
      (is (= "test-deployment" (:deployment client)))
      (is (= "test-service" (:service client)))
      ;; http-client may be nil when running outside kubernetes cluster
      (is (contains? client :http-client)))))

(deftest halt-key-ark-k8s-client-test
  (testing ":ark/k8s-client halt closes http-client"
    (let [config {:k8s-namespace "test-ns"
                  :k8s-deployment "test-deployment"
                  :k8s-service "test-service"}
          client (ig/init-key :ark/k8s-client {:config config})]
      ;; Should not throw when halting (even if http-client is nil)
      (is (nil? (ig/halt-key! :ark/k8s-client client))))))
