(ns isaac.foreman.core
  "Start / signal / status / list — I/O orchestration around the pure engine."
  (:require
    [clojure.string :as str]
    [isaac.config.loader :as loader]
    [isaac.foreman.machine :as machine]
    [isaac.foreman.observer :as observer]
    [isaac.foreman.store :as store]
    [isaac.fs :as fs]
    [isaac.tool.memory :as memory]))

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

(defn- pending-entry [action-name spec]
  {:name action-name
   :type (classify-action spec)})

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

(defn- apply-actions! [machine shared action-names]
  (let [resolved (mapv (fn [n]
                         (let [spec (resolve-action machine n shared)]
                           {:name n :spec spec :type (classify-action spec)}))
                       action-names)
        pending  (->> resolved
                      (remove #(= :log (:type %)))
                      (mapv #(pending-entry (:name %) (:spec %))))]
    (doseq [{:keys [type spec]} resolved]
      (when (= :log type)
        (execute-log! spec)))
    pending))

(defn signal!
  "Fire `event` at an existing instance. Unhandled events warn and stay put."
  [{:keys [root fs machine id event now] :as opts}]
  (let [cfg     (load-cfg opts)
        table   (machine-table cfg machine)
        inst    (store/get-instance {:fs fs :root root :machine machine :id id})
        shared  (get-in cfg [:foreman :actions])
        ts      (now-iso now)]
    (when-not table
      (throw (ex-info (str "unknown machine: " (name machine))
                      {:machine machine})))
    (when-not inst
      (throw (ex-info (str "unknown instance: " (name id))
                      {:id id})))
    (let [result (machine/step table (:state inst) event)]
      (if (= :unhandled (:status result))
        (do
          (store/record-unhandled! {:fs      fs
                                    :root    root
                                    :machine machine
                                    :id      id
                                    :state   (:state inst)
                                    :event   event
                                    :now     ts})
          (binding [*out* *err*]
            (println (str "unhandled: " (name event) " (state " (name (:state inst)) ")")))
          inst)
        (let [pending (apply-actions! table shared (:actions result))
              updated (store/record-transition! {:fs      fs
                                                 :root    root
                                                 :machine machine
                                                 :id      id
                                                 :from    (:state inst)
                                                 :to      (:state result)
                                                 :event   event
                                                 :actions (:actions result)
                                                 :now     ts})
              with-p  (store/set-pending! {:fs      fs
                                           :root    root
                                           :machine machine
                                           :id      id
                                           :pending pending})]
          (observer/notify! {:observers (:observers table)
                             :machine   machine
                             :id        id
                             :from      (:state inst)
                             :to        (:state result)
                             :event     event})
          (println (format-transition id (:state inst) (:state result) event))
          with-p)))))

(defn- history-line [rec]
  (case (:type rec)
    :transition (str (name (:event rec)) ": "
                     (name (:from rec)) " -> " (name (:to rec)))
    :unhandled  (str "unhandled: " (name (:event rec)))
    (pr-str rec)))

(defn- pending-line [entry]
  (str (name (:name entry)) " (" (name (:type entry)) ")"))

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
        hist    (map history-line history)
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
