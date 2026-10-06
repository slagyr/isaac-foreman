(ns isaac.foreman-steps
  "Foreman feature helpers. Reuses Isaac root / config / CLI / clock steps.
   Registers a run-wrapper so `the current time is` (memory/*now*) is bound
   during in-process `isaac is run with`."
  (:require
    [gherclj.core :as g :refer [defwhen helper!]]
    [isaac.foundation.cli-steps :as cli-steps]
    [isaac.agent.tool.memory :as memory]
    [isaac.foundation.nexus :as nexus]))

(helper! isaac.foreman-steps)

(cli-steps/register-isaac-run-wrapper!
  (fn [thunk]
    (when (g/get :fresh-process-next?)
      (g/dissoc! :fresh-process-next?)
      (nexus/deregister! [:sessions]))
    (if-let [current-time (g/get :current-time)]
      (binding [memory/*now* current-time]
        (thunk))
      (thunk))))

(defn fresh-process-next! []
  (g/assoc! :fresh-process-next? true))

(defwhen "the next isaac command starts in a fresh process" isaac.foreman-steps/fresh-process-next!
  "Drops the registered Agent session store for the next CLI invocation, as in a fresh shell.")
