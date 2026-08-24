(ns isaac.foreman.cli
  "isaac foreman — start, signal, status, and list machine instances."
  (:require
    [clojure.string :as str]
    [clojure.tools.cli :as tools-cli]
    [isaac.cli.api :as cli-api]
    [isaac.config.root :as root]
    [isaac.foreman.core :as core]
    [isaac.fs :as fs]
    [isaac.tool.memory :as memory]))

(def option-spec
  [["-h" "--help" "Show help"]
   [nil  "--state STATE" "Filter list to instances currently in this state"]])

(def ^:private help-text
  (str/join "\n"
            ["Usage: isaac foreman <subcommand> [options]"
             ""
             "Drive and inspect machine instances."
             ""
             "Subcommands:"
             "  start  <machine> <id>   Birth an instance at the machine's :initial state"
             "  signal <machine> <id> <event>   Fire an event at an instance"
             "  status <machine> <id>   Show one instance (state, since, pending, history)"
             "  list   <machine>        Survey a machine's instances"
             ""
             "Options:"
             "  --state STATE  Filter list to the given current state"
             "  -h, --help     Show help"]))

(defn- print-err! [msg]
  (binding [*out* *err*]
    (println msg)))

(defn- keywordize [s]
  (when s
    (if (keyword? s)
      s
      (keyword (if (str/starts-with? (str s) ":")
                 (subs (str s) 1)
                 (str s))))))

(defn- clock-now [opts]
  (or (:now opts) memory/*now*))

(defn- env [opts]
  (let [root-dir (or (:root opts) (root/default-root opts))
        fs*      (or (:fs opts) (fs/instance) (fs/real-fs))]
    {:root root-dir :fs fs* :now (clock-now opts)}))

(defn- fail [e]
  (print-err! (or (ex-message e) (.getMessage e)))
  1)

(defn- run-start [opts machine id]
  (try
    (core/start! (assoc (env opts) :machine machine :id id))
    0
    (catch Exception e (fail e))))

(defn- run-signal [opts machine id event]
  (try
    (core/signal! (assoc (env opts) :machine machine :id id :event (keywordize event)))
    0
    (catch Exception e (fail e))))

(defn- run-status [opts machine id]
  (try
    (core/status (assoc (env opts) :machine machine :id id))
    0
    (catch Exception e (fail e))))

(defn- run-list [opts machine state]
  (try
    (core/list-instances (assoc (env opts) :machine machine :state (keywordize state)))
    0
    (catch Exception e (fail e))))

(defn run [opts]
  (let [raw      (or (:_raw-args opts) [])
        sub      (first raw)
        rest-args (rest raw)
        {:keys [options arguments errors]} (tools-cli/parse-opts rest-args option-spec)]
    (cond
      (seq errors)
      (do (doseq [e errors] (print-err! e)) 1)

      (or (:help options) (nil? sub) (= "help" sub))
      (do (println help-text) 0)

      (= "start" sub)
      (let [[machine id] arguments]
        (if (or (str/blank? machine) (str/blank? id))
          (do (print-err! "Usage: isaac foreman start <machine> <id>") 1)
          (run-start opts machine id)))

      (= "signal" sub)
      (let [[machine id event] arguments]
        (if (or (str/blank? machine) (str/blank? id) (str/blank? event))
          (do (print-err! "Usage: isaac foreman signal <machine> <id> <event>") 1)
          (run-signal opts machine id event)))

      (= "status" sub)
      (let [[machine id] arguments]
        (if (or (str/blank? machine) (str/blank? id))
          (do (print-err! "Usage: isaac foreman status <machine> <id>") 1)
          (run-status opts machine id)))

      (= "list" sub)
      (let [[machine] arguments]
        (if (str/blank? machine)
          (do (print-err! "Usage: isaac foreman list <machine>") 1)
          (run-list opts machine (:state options))))

      :else
      (do
        (print-err! (str "Unknown foreman subcommand: " sub))
        (println help-text)
        1))))

(defmethod cli-api/run :foreman [_id opts]
  (run opts))

(defmethod cli-api/option-spec :foreman [_id]
  option-spec)

(defmethod cli-api/help :foreman [_id]
  help-text)

(defmethod cli-api/subcommands :foreman [_id]
  [{:name "start" :summary "Birth an instance at the machine's :initial state"}
   {:name "signal" :summary "Fire an event at an instance"}
   {:name "status" :summary "Show one instance (state, since, pending, history)"}
   {:name "list" :summary "Survey a machine's instances"}])
