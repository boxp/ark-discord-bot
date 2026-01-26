(ns ark-discord-bot.kubernetes.client
    "Kubernetes API client for managing ARK server deployments."
    (:require [babashka.http-client :as http]
              [cheshire.core :as json]
              [clojure.string :as str]))

(defn create-client
  "Create a Kubernetes client configuration."
  [namespace deployment service]
  {:namespace namespace
   :deployment deployment
   :service service
   :api-server (or (System/getenv "KUBERNETES_SERVICE_HOST")
                   "kubernetes.default.svc")
   :token-path "/var/run/secrets/kubernetes.io/serviceaccount/token"
   :ca-path "/var/run/secrets/kubernetes.io/serviceaccount/ca.crt"})

(defn- read-token
  "Read service account token."
  [client]
  (try
    (str/trim (slurp (:token-path client)))
    (catch Exception _ nil)))

(defn- api-url
  "Build Kubernetes API URL."
  [client path]
  (str "https://" (:api-server client) path))

(defn- deployment-url
  "Get deployment API URL."
  [client]
  (api-url client
           (format "/apis/apps/v1/namespaces/%s/deployments/%s"
                   (:namespace client)
                   (:deployment client))))

(defn parse-deployment-status
  "Parse deployment status from API response."
  [response]
  (let [status (:status response)]
    {:replicas (get status :replicas 0)
     :ready (get status :readyReplicas 0)
     :available? (pos? (get status :availableReplicas 0))}))

(defn is-transient-error?
  "Check if error is transient (etcd issues, etc.)."
  [ex]
  (let [body (get (ex-data ex) :body "")]
    (or (str/includes? body "etcdserver:")
        (str/includes? body "context deadline exceeded"))))

(defn get-deployment-status
  "Get deployment status from Kubernetes API."
  [client]
  (let [token (read-token client)
        resp (http/get (deployment-url client)
                       {:headers {"Authorization" (str "Bearer " token)}
                        :throw false})]
    (if (= 200 (:status resp))
      (parse-deployment-status (json/parse-string (:body resp) true))
      (throw (ex-info "Failed to get deployment"
                      {:status (:status resp) :body (:body resp)})))))

(defn restart-deployment
  "Restart deployment by patching with new annotation."
  [client]
  (let [token (read-token client)
        patch {:spec
               {:template
                {:metadata
                 {:annotations
                  {"kubectl.kubernetes.io/restartedAt"
                   (str (java.time.Instant/now))}}}}}
        resp (http/patch (deployment-url client)
                         {:headers {"Authorization" (str "Bearer " token)
                                    "Content-Type" "application/strategic-merge-patch+json"}
                          :body (json/generate-string patch)
                          :throw false})]
    (when (not= 200 (:status resp))
      (throw (ex-info "Failed to restart deployment"
                      {:status (:status resp) :body (:body resp)})))))
