(ns build
  "Build script for ark-discord-bot using tools.build."
  (:require [clojure.tools.build.api :as b]))

(def lib 'ark-discord-bot/ark-discord-bot)
(def class-dir "target/classes")

(defn- get-version
  "Get version string. Uses git rev count if available, otherwise uses BUILD_VERSION env var or 'dev'."
  []
  (let [git-revs (try (b/git-count-revs nil) (catch Exception _ nil))
        env-version (System/getenv "BUILD_VERSION")]
    (cond
      git-revs (format "0.1.%s" git-revs)
      env-version env-version
      :else "0.1.0-dev")))

(defn- get-basis []
  (b/create-basis {:project "deps.edn"}))

(defn- get-uber-file [version]
  (format "target/%s-%s-standalone.jar" (name lib) version))

(defn clean
  "Delete the target directory."
  [_]
  (b/delete {:path "target"}))

(defn uberjar
  "Build an uberjar with AOT compilation for GraalVM native-image."
  [_]
  (let [version (get-version)
        basis (get-basis)
        uber-file (get-uber-file version)]
    (clean nil)
    (b/copy-dir {:src-dirs ["src" "resources"]
                 :target-dir class-dir})
    ;; AOT compile with direct linking for GraalVM compatibility
    (b/compile-clj {:basis basis
                    :src-dirs ["src"]
                    :class-dir class-dir
                    :ns-compile ['ark-discord-bot.main]
                    :compiler-opts {:direct-linking true
                                    :elide-meta [:doc :file :line :added]}})
    (b/uber {:class-dir class-dir
             :uber-file uber-file
             :basis basis
             :main 'ark-discord-bot.main})
    (println "Built:" uber-file)))
