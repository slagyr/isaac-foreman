(ns isaac.foreman-feature-bootstrap
  "Loaded after isaac.**-steps. Drops colliding step templates so foreman
   features can share the foundation/session/server step surface without
   ambiguous matches (isaac-iz35 / isaac-hooks pattern)."
  (:require
    [isaac.foundation.cli-steps :as cli-steps]
    [clojure.string :as str]
    [isaac.foundation.logger :as log]
    [gherclj.core :as g]
    [isaac.agent.turn.worker :as worker]))

(log/set-output! :memory)

(defonce ^:private parse-argv-wrapped?
  (do
    (alter-var-root #'cli-steps/parse-argv
      (fn [orig]
        (fn [args]
          (orig (if-let [id (g/get :turn-id)]
                  (str/replace args "#turn-id" id)
                  args)))))
    true))

;; isaac.agent.turn.submit/submit! is queue-only (isaac-e9jl/isaac-2lc4):
;; a :turn action fired by `foreman signal` enqueues a durable record but
;; running it needs a queue tick — in production a running server's own
;; tick picks it up (same as hail: "hail ends at queue"). isaac.foreman.core
;; already calls isaac.agent.turn.worker/tick! synchronously wherever it
;; submits or resubmits a turn (signal! -> consume! -> retry!, and retry!
;; itself), so the queue is always ticked inside `main/run`, in the CLI's
;; own nexus scope with the command's own freshly-loaded config; tick! only
;; claims and starts the turn before returning, though (isaac-e9jl), so the
;; started future can still be running when the CLI process exits.
;;
;; This postflight only needs to await-idle! — not tick! again. An extra
;; tick! here runs outside the command's nexus scope with no ambient config
;; installed, so it falls back to its own reload; for a resource-pool-gated
;; turn that reload can land a config composed without the scripted pool's
;; entity-dir file still in play, re-processing an already-correctly-held
;; record and wake-failing it as :unknown-resource-pool. await-idle! alone
;; just waits out whatever tick! already started; a no-op when nothing is.
(cli-steps/register-isaac-run-postflight!
  (fn []
    (worker/await-idle!)
    (when-let [output (g/get :output)]
      (when-let [[_ id] (re-find #"submitted\s+([a-z0-9-]+)" output)]
        (g/assoc! :turn-id id)))))

(def ^:private session-ns 'isaac.agent.session.session-steps)
(def ^:private configurator-ns 'isaac.http.configurator-steps)
(def ^:private harness-ns 'isaac.foundation.harness-config-steps)
(def ^:private server-ns 'isaac.http.server-steps)

(defn- without-templates [entries templates]
  (let [drop? (set (or templates []))]
    (vec (remove #(contains? drop? (:template %)) entries))))

(defn- server-owns-config? [registry]
  (some (fn [[ns-sym entries]]
          (when (= ns-sym server-ns)
            (some #(= "config:" (:template %)) entries)))
        registry))

(when-let [registry-var (some-> (find-ns 'gherclj.core) ns-interns (get 'registry))]
  (swap! @registry-var
         (fn [m]
           (into {}
                 (map (fn [[ns-sym entries]]
                        [ns-sym
                         (cond
                           (= ns-sym configurator-ns)
                           (without-templates entries ["default Grover setup"])

                           (= ns-sym session-ns)
                           (without-templates entries
                                              (cond-> []
                                                (server-owns-config? m) (conj "config:")))

                           (= ns-sym harness-ns)
                           (without-templates entries
                                              (when (server-owns-config? m) ["config:"]))

                           :else entries)]))
                 m))))
