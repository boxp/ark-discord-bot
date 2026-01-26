(ns ark-discord-bot.kubernetes.client-test
    "Tests for Kubernetes client."
    (:require [ark-discord-bot.kubernetes.client :as k8s]
              [clojure.test :refer [deftest is testing]]))

(deftest test-create-client
  (testing "create-client returns client map"
    (let [c (k8s/create-client "default" "ark-server" "ark-service")]
      (is (= "default" (:namespace c)))
      (is (= "ark-server" (:deployment c)))
      (is (= "ark-service" (:service c))))))

(deftest test-parse-deployment-status-ready
  (testing "parse-deployment-status extracts ready replicas"
    (let [response {:status {:replicas 1
                             :readyReplicas 1
                             :availableReplicas 1}}
          status (k8s/parse-deployment-status response)]
      (is (= 1 (:replicas status)))
      (is (= 1 (:ready status)))
      (is (true? (:available? status))))))

(deftest test-parse-deployment-status-not-ready
  (testing "parse-deployment-status handles not ready"
    (let [response {:status {:replicas 1
                             :readyReplicas 0
                             :availableReplicas 0}}
          status (k8s/parse-deployment-status response)]
      (is (= 0 (:ready status)))
      (is (false? (:available? status))))))

(deftest test-is-transient-error?
  (testing "is-transient-error? detects etcd errors"
    (is (k8s/is-transient-error?
         (ex-info "error" {:body "etcdserver: leader changed"})))
    (is (k8s/is-transient-error?
         (ex-info "error" {:body "etcdserver: request timed out"}))))

  (testing "is-transient-error? returns false for other errors"
    (is (false? (k8s/is-transient-error?
                 (ex-info "error" {:body "not found"}))))))

;; Run tests when loaded
(clojure.test/run-tests 'ark-discord-bot.kubernetes.client-test)
