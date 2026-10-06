(ns isaac.foreman-steps
  "Foreman feature helpers. Reuses Isaac root / config / CLI / clock steps.
   Registers a run-wrapper so `the current time is` (memory/*now*) is bound
   during in-process `isaac is run with`."
  (:require
    [gherclj.core :as g :refer [defwhen helper!]]
    [isaac.foundation.cli-steps :as cli-steps]
    [isaac.agent.tool.memory :as memory]
    [isaac.agent.turn.worker :as worker]
    [isaac.foundation.scheduler.runtime :as scheduler]
    [isaac.foundation.nexus :as nexus]))

(helper! isaac.foreman-steps)

(defonce ^:private server-worker* (atom nil))

(defn- ensure-server-worker! []
  (when-not @server-worker*
    (let [sched (scheduler/create {})]
      (nexus/register! [:scheduler] sched)
      (reset! server-worker* (worker/start! {})))))

(cli-steps/register-isaac-run-wrapper!
  (fn [thunk]
    (let [fresh? (g/get :fresh-process-next?)]
      (when fresh?
        (g/dissoc! :fresh-process-next?)
        (nexus/deregister! [:sessions]))
      (when-not fresh?
        (ensure-server-worker!))
      (let [run #(if-let [current-time (g/get :current-time)]
                   (binding [memory/*now* current-time] (thunk))
                   (thunk))]
        (if fresh?
          (let [handle @server-worker*]
            (when handle
              (worker/stop! handle)
              (reset! server-worker* nil))
            (try (run)
                 (finally (ensure-server-worker!))))
          (run))))))

(defn fresh-process-next! []
  (g/assoc! :fresh-process-next? true))

(defwhen "the next isaac command starts in a fresh process" isaac.foreman-steps/fresh-process-next!
  "Drops the registered Agent session store for the next CLI invocation, as in a fresh shell.")
