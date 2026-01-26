(ns ark-discord-bot.effects.kubernetes-test
    "Tests for Kubernetes client."
    (:require [ark-discord-bot.effects.kubernetes :as k8s]
              [clojure.test :refer [deftest is testing]]))

(deftest test-create-client
  (testing "create-client returns client map"
    (let [c (k8s/create-client "default" "ark-server" "ark-service")]
      (is (= "default" (:namespace c)))
      (is (= "ark-server" (:deployment c)))
      (is (= "ark-service" (:service c)))))

  (testing "create-client includes CA path for SSL"
    (let [c (k8s/create-client "default" "ark-server" "ark-service")]
      (is (= "/var/run/secrets/kubernetes.io/serviceaccount/ca.crt"
             (:ca-path c)))))

  (testing "create-client includes http-client key"
    (let [c (k8s/create-client "default" "ark-server" "ark-service")]
      ;; http-client is nil outside K8s cluster (no CA cert file)
      ;; but the key should exist in the client map
      (is (contains? c :http-client)))))

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
(clojure.test/run-tests 'ark-discord-bot.effects.kubernetes-test)
