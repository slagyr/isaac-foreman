(ns isaac.foreman.core
  "Start / signal / status / list — I/O orchestration around the pure engine."
  (:require
    [clojure.string :as str]
    [isaac.config.loader :as loader]
    [isaac.foreman.machine :as machine]
    [isaac.foreman.observer :as observer]
    [isaac.foreman.store :as store]
    [isaac.fs :as fs]
    [isaac.tool.memory :as memory]
    [isaac.turn.submit :as turn-submit]
    [isaac.turn.worker :as worker]))

(defn now-iso
  "ISO-8601 instant, truncated to seconds when no fractional part is needed."
  ([]
   (now-iso nil))
  ([now]
   (let [inst (or now memory/*now* (java.time.Instant/now))
         s    (str inst)]
     (if (str/ends-with? s "Z")
       (str/replace s #"\.\d+Z$" "Z")
       s))))

(defn- load-cfg [{:keys [root fs]}]
  (loader/load-config! root fs "foreman"))

(defn machine-table
  "Look up a named machine in config. Returns nil when missing."
  [cfg machine-name]
  (get-in cfg [:machines (name machine-name)]))

(defn- resolve-action [machine action-name shared]
  (or (get-in machine [:actions action-name])
      (get shared action-name)))

(defn- classify-action [spec]
  (or (:type spec) :unknown))

(defn- execute-log! [spec]
  (when-let [msg (:message spec)]
    (println msg)))

(defn- pending-entry [action-name spec machine id envelope]
  (cond-> {:name action-name :type (classify-action spec)}
    (= :turn (:type spec))
    (assoc :key (str (name machine) "/" (name id) "/" (:id envelope) "/" (name action-name))
           :data (:data envelope))))

(defn- fill-prompt [template machine id data]
  (str/replace (or template "") #"\{\{(machine|instance|data\.[^}]+)\}\}"
               (fn [[_ field]]
                 (str (case field
                        "machine" (name machine)
                        "instance" (name id)
                        (get data (keyword (subs field 5)) ""))))))

(defn- submit-pending! [opts table shared entry]
  (let [spec (resolve-action table (:name entry) shared)]
    (try
      (let [request (turn-submit/submit!
                      (merge (select-keys opts [:fs :root :machine :id])
                             {:frequencies (:frequencies spec)
                              :resource-pools (:resource-pools spec)
                              :prompt (fill-prompt (:prompt spec) (:machine opts) (:id opts) (:data entry))
                              :observers [[:foreman (name (:machine opts)) (name (:id opts))]]
                              :key (:key entry)}))]
        (assoc entry :request-id (:id request) :error nil))
      (catch Exception e
        (assoc entry :error (str/replace (ex-message e) #"^unknown resource pool: " "unknown resource pool "))))))

(defn retry!
  "Resubmit persisted actions; Agent's idempotency key handles a lost acknowledgement."
  [{:keys [machine id] :as opts}]
  (let [cfg (load-cfg opts)
        table (machine-table cfg machine)
        shared (get-in cfg [:foreman :actions])
        entries (:pending-actions (store/get-instance opts))
        updated (mapv (fn [entry]
                        (if (and (= :turn (:type entry)) (nil? (:request-id entry)))
                          (submit-pending! opts table shared entry)
                          entry)) entries)]
    (store/set-pending! (assoc opts :pending updated))
    (when (some #(and (= :turn (:type %)) (:request-id %)) updated)
      (worker/tick!))
    (doseq [{:keys [name type request-id]} updated :when (and (= :turn type) request-id)]
      (println (str (clojure.core/name name) " (turn) submitted " request-id)))
    updated))

(defn- format-transition [id from to event]
  (str (name id) ": " (name from) " -> " (name to) " (" (name event) ")"))

(defn start!
  "Birth an instance at the machine's :initial state."
  [{:keys [root fs machine id now] :as opts}]
  (let [cfg   (load-cfg opts)
        table (machine-table cfg machine)]
    (when-not table
      (throw (ex-info (str "unknown machine: " (name machine))
                      {:machine machine})))
    (let [inst (store/create-instance! {:fs      fs
                                        :root    root
                                        :machine machine
                                        :id      id
                                        :state   (:initial table)
                                        :now     (now-iso now)})]
      (println (str (name id) ": " (name (:state inst))))
      inst)))

(defn- apply-actions! [machine shared action-names machine-name id envelope]
  (let [resolved (mapv (fn [n]
                         (let [spec (resolve-action machine n shared)]
                           {:name n :spec spec :type (classify-action spec)}))
                       action-names)
        pending  (->> resolved
                      (remove #(= :log (:type %)))
                      (mapv #(pending-entry (:name %) (:spec %) machine-name id envelope)))]
    (doseq [{:keys [type spec]} resolved]
      (when (= :log type)
        (execute-log! spec)))
    pending))

(defn- consume! [opts table shared envelope]
  (let [{:keys [fs root machine id]} opts
        {:keys [event source data crew session request-id]} envelope
        ts   (:at envelope)
        inst (store/get-instance opts)
        result (machine/step table (:state inst) event)
        details (merge (select-keys opts [:fs :root :machine :id])
                       (select-keys envelope [:source :data :crew :session :request-id])
                       {:event event :event-id (:id envelope) :now ts})]
    (if (= :unhandled (:status result))
      (do (store/record-unhandled! (assoc details :state (:state inst)))
          (binding [*out* *err*]
            (println (str "unhandled: " (name event) " (state " (name (:state inst)) ")")))
          inst)
      (let [pending (apply-actions! table shared (:actions result) machine id envelope)
            _ (store/record-transition! (assoc details :from (:state inst) :to (:state result)
                                                :actions (:actions result)))
            _ (when (seq pending) (store/set-pending! (assoc opts :pending pending)))]
        (observer/notify! {:observers (:observers table) :machine machine :id id
                           :from (:state inst) :to (:state result) :event event})
        (println (format-transition id (:state inst) (:state result) event))
        (retry! opts)
        (store/get-instance opts)))))

(defn- drain! [opts table shared]
  (doseq [envelope (store/unconsumed opts)]
    (consume! opts table shared envelope)))

(defn signal!
  "Durably receive an event, then drain prior events in arrival order."
  [{:keys [root fs machine id event now event-id source data crew session request-id] :as opts}]
  (let [cfg    (load-cfg opts)
        table  (machine-table cfg machine)
        inst   (store/get-instance opts)
        shared (get-in cfg [:foreman :actions])]
    (when-not table
      (throw (ex-info (str "unknown machine: " (name machine)) {:machine machine})))
    (when-not inst
      (throw (ex-info (str "unknown instance: " (name id)) {:id id})))
    (let [eid (or event-id (str (java.util.UUID/randomUUID)))
          envelope (cond-> {:id eid :machine (name machine) :instance (name id)
                            :event event :source (or source :cli) :at (now-iso now)}
                     data (assoc :data data)
                     crew (assoc :crew crew)
                     session (assoc :session session)
                     request-id (assoc :request-id request-id))
          receipt (store/receive! (assoc opts :event-id eid) envelope)]
      (when-not (:duplicate receipt)
        (drain! opts table shared))
      receipt)))

(defn resume!
  "Sweep all persisted instances on server start, applying each unconsumed event."
  [{:keys [root fs] :as opts}]
  (doseq [instance (store/all-instance-keys opts)]
    (let [params (merge opts instance)
          cfg (load-cfg params)]
      (when-let [table (machine-table cfg (:machine instance))]
        (drain! params table (get-in cfg [:foreman :actions]))
        (retry! params)))))

(defn- history-line [rec]
  (let [suffix (when (:id rec) (str "  [" (:id rec) "] via " (name (:source rec))
                                  (when (:crew rec) (str " (crew " (:crew rec) ", session " (:session rec) ")"))))]

  (case (:type rec)
    :transition (str (name (:event rec)) ": "
                     (name (:from rec)) " -> " (name (:to rec)) suffix)
    :unhandled  (str "unhandled: " (name (:event rec)) suffix)
    :duplicate (str "duplicate: " (name (:event rec)) suffix)
    :received nil
    (pr-str rec))))

(defn- pending-line [entry]
  (str (name (:name entry)) " (" (name (:type entry)) ")"
       (when-let [error (:error entry)] (str " failed: " error))
       (when-let [request-id (:request-id entry)] (str " submitted " request-id))))

(defn format-status
  "Render one instance for `foreman status`."
  [inst history]
  (let [header (str (name (:id inst)) "  " (name (:state inst))
                    "  since " (or (:since inst) ""))
        pending (when (seq (:pending-actions inst))
                  (str "pending: "
                       (->> (:pending-actions inst)
                            (map pending-line)
                            (str/join ", "))))
        hist    (keep history-line history)
        action-names (mapcat :actions (filter #(= :transition (:type %)) history))]
    (->> (concat [header]
                 (when pending [pending])
                 (map name action-names)
                 hist)
         (remove nil?)
         (str/join "\n"))))

(defn status
  [{:keys [root fs machine id] :as opts}]
  (let [inst (store/get-instance {:fs fs :root root :machine machine :id id})]
    (when-not inst
      (throw (ex-info (str "unknown instance: " (name id))
                      {:id id})))
    (let [hist (store/history {:fs fs :root root :machine machine :id id})]
      (println (format-status inst hist))
      inst)))

(defn format-list-row [inst]
  (str (name (:id inst)) "  " (name (:state inst))
       "  since " (or (:since inst) "")))

(defn list-instances
  [{:keys [root fs machine state] :as opts}]
  (let [rows (store/list-instances {:fs fs :root root :machine machine :state state})]
    (doseq [row rows]
      (println (format-list-row row)))
    rows))
