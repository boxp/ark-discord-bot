(ns ark-discord-bot.main
    "Main entry point for the ARK Discord Bot.
   Uses Integrant for system lifecycle management."
    (:require [ark-discord-bot.system :as system])
    (:gen-class))

(defn- log
  "Simple logging helper."
  [level msg]
  (println (str "[" (name level) "] " msg)))

(defn -main
  "Application entry point."
  [& _args]
  (log :info "ARK Discord Bot starting...")
  (let [sys (system/start!)]
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread. (fn []
                                 (log :info "Shutting down...")
                                 (system/stop! sys)
                                 (log :info "Shutdown complete."))))
    @(promise)))
