(ns ark-discord-bot.effects.kubernetes
    "Kubernetes API client for managing ARK server deployments.
   All API functions return core.async channels."
    (:require [babashka.fs :as fs]
              [babashka.http-client :as http]
              [cheshire.core :as json]
              [clojure.core.async :as async]
              [clojure.string :as str]))

(defn- create-http-client
  "Create HTTP client with SSL context for K8s API.
   Uses insecure mode for in-cluster communication where CA cert validation
   is handled at the network level."
  [ca-path]
  (if (fs/exists? ca-path)
    ;; In-cluster: use insecure SSL (cluster network is trusted)
    (http/client {:ssl-context {:insecure true}})
    ;; Outside cluster: use default SSL
    nil))

(defn create-client
  "Create a Kubernetes client configuration."
  [namespace deployment service]
  (let [ca-path "/var/run/secrets/kubernetes.io/serviceaccount/ca.crt"]
    {:namespace namespace
     :deployment deployment
     :service service
     :api-server (or (System/getenv "KUBERNETES_SERVICE_HOST")
                     "kubernetes.default.svc")
     :token-path "/var/run/secrets/kubernetes.io/serviceaccount/token"
     :ca-path ca-path
     :http-client (create-http-client ca-path)}))

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

(defn- request-opts
  "Build request options with optional HTTP client."
  [client headers]
  (cond-> {:headers headers :throw false}
          (:http-client client) (assoc :client (:http-client client))))

(defn- get-deployment-status-impl
  "Get deployment status from Kubernetes API (synchronous implementation)."
  [client]
  (let [token (read-token client)
        opts (request-opts client {"Authorization" (str "Bearer " token)})
        resp (http/get (deployment-url client) opts)]
    (if (= 200 (:status resp))
      (parse-deployment-status (json/parse-string (:body resp) true))
      (throw (ex-info "Failed to get deployment"
                      {:status (:status resp) :body (:body resp)})))))

(defn get-deployment-status
  "Get deployment status from Kubernetes API. Returns a channel."
  [client]
  (async/thread
    (get-deployment-status-impl client)))

(defn- build-restart-patch
  "Build patch payload for restarting deployment."
  []
  {:spec {:template {:metadata {:annotations
                                {"kubectl.kubernetes.io/restartedAt"
                                 (str (java.time.Instant/now))}}}}})

(defn- execute-patch
  "Execute PATCH request against Kubernetes API."
  [client token patch]
  (let [opts (-> (request-opts client {"Authorization" (str "Bearer " token)
                                       "Content-Type" "application/strategic-merge-patch+json"})
                 (assoc :body (json/generate-string patch)))]
    (http/patch (deployment-url client) opts)))

(defn- restart-deployment-impl
  "Restart deployment by patching with new annotation (synchronous implementation)."
  [client]
  (let [token (read-token client)
        resp (execute-patch client token (build-restart-patch))]
    (if (= 200 (:status resp))
      {:success true}
      {:error (ex-info "Failed to restart deployment"
                       {:status (:status resp) :body (:body resp)})})))

(defn restart-deployment
  "Restart deployment by patching with new annotation.
   Returns a channel with {:success true} or {:error <exception>}."
  [client]
  (async/thread
    (try
      (restart-deployment-impl client)
      (catch Exception e {:error e}))))
