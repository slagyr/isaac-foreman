(ns isaac.foreman.feature-runner
  "JVM feature entry point: release async agent workers after Gherclj finishes."
  (:require
    [gherclj.main :as gherclj]))

(defn -main [& args]
  (try
    (apply gherclj/-main args)
    (finally
      (shutdown-agents))))
