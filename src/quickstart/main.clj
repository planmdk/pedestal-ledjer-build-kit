(ns quickstart.main
  "Entry point: `clojure -X:run`."
  (:require
   [donut.system :as ds]
   [quickstart.system]))

(defn start-service
  "Start the :prod system and keep running until the JVM is stopped."
  [_opts]
  (let [system (ds/signal (ds/system :prod) ::ds/start)]
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread. ^Runnable (fn [] (ds/signal system ::ds/stop))))
    system))
