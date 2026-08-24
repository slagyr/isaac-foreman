(ns isaac.foreman-steps
  "Foreman feature helpers. Reuses Isaac root / config / CLI / clock steps.
   Registers a run-wrapper so `the current time is` (memory/*now*) is bound
   during in-process `isaac is run with`."
  (:require
    [gherclj.core :as g :refer [helper!]]
    [isaac.foundation.cli-steps :as cli-steps]
    [isaac.tool.memory :as memory]))

(helper! isaac.foreman-steps)

(cli-steps/register-isaac-run-wrapper!
  (fn [thunk]
    (if-let [current-time (g/get :current-time)]
      (binding [memory/*now* current-time]
        (thunk))
      (thunk))))
