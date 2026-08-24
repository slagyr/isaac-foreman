(ns isaac.foreman.store
  "File-backed machine-instance store.
   foreman/<machine>/<id>.edn holds {:state :context :pending-actions :since}
   and foreman/<machine>/<id>.events.ednl is the append-only history."
  (:require
    [clojure.edn :as edn]
    [clojure.string :as str]
    [isaac.fs :as fs]))

(defn- machine-dir [{:keys [root machine]}]
  (str root "/foreman/" (name machine)))

(defn- instance-edn-path [opts]
  (str (machine-dir opts) "/" (name (:id opts)) ".edn"))

(defn- instance-events-path [opts]
  (str (machine-dir opts) "/" (name (:id opts)) ".events.ednl"))

(defn- read-edn [fs* path]
  (when (fs/exists? fs* path)
    (edn/read-string (fs/slurp fs* path))))

(defn- write-edn! [fs* path data]
  (fs/mkdirs fs* (fs/parent path))
  (fs/spit fs* path (pr-str data)))

(defn- append-event! [fs* path rec]
  (fs/mkdirs fs* (fs/parent path))
  (fs/spit fs* path (str (pr-str rec) "\n") :append true))

(defn- read-events [fs* path]
  (when (fs/exists? fs* path)
    (->> (str/split-lines (fs/slurp fs* path))
         (remove str/blank?)
         (mapv edn/read-string))))

(defn- with-id [id rec]
  (assoc rec :id (name id)))

(defn create-instance!
  [{:keys [fs root machine id state now] :as opts}]
  (let [edn-path (instance-edn-path opts)]
    (when (fs/exists? fs edn-path)
      (throw (ex-info (str "already exists: " (name id))
                      {:machine machine :id id})))
    (let [rec {:state            state
               :context          {}
               :pending-actions  []
               :since            now}]
      (write-edn! fs edn-path rec)
      (fs/mkdirs fs (fs/parent (instance-events-path opts)))
      (when-not (fs/exists? fs (instance-events-path opts))
        (fs/spit fs (instance-events-path opts) ""))
      (with-id id rec))))

(defn get-instance
  [{:keys [fs] :as opts}]
  (when-let [rec (read-edn fs (instance-edn-path opts))]
    (with-id (:id opts) rec)))

(defn record-transition!
  [{:keys [fs from to event actions now] :as opts}]
  (let [edn-path (instance-edn-path opts)
        ev-path  (instance-events-path opts)
        rec      (or (read-edn fs edn-path)
                     (throw (ex-info (str "unknown instance: " (name (:id opts)))
                                     {:id (:id opts)})))
        updated  (assoc rec :state to :since now)]
    (append-event! fs ev-path {:type    :transition
                               :from    from
                               :to      to
                               :event   event
                               :actions (vec actions)
                               :at      now})
    (write-edn! fs edn-path updated)
    (with-id (:id opts) updated)))

(defn record-unhandled!
  [{:keys [fs state event now] :as opts}]
  (let [edn-path (instance-edn-path opts)
        ev-path  (instance-events-path opts)
        rec      (or (read-edn fs edn-path)
                     (throw (ex-info (str "unknown instance: " (name (:id opts)))
                                     {:id (:id opts)})))]
    (append-event! fs ev-path {:type  :unhandled
                               :event event
                               :state state
                               :at    now})
    (with-id (:id opts) rec)))

(defn history
  [{:keys [fs] :as opts}]
  (or (read-events fs (instance-events-path opts)) []))

(defn set-pending!
  [{:keys [fs pending] :as opts}]
  (let [edn-path (instance-edn-path opts)
        rec      (or (read-edn fs edn-path)
                     (throw (ex-info (str "unknown instance: " (name (:id opts)))
                                     {:id (:id opts)})))
        updated  (assoc rec :pending-actions (vec pending))]
    (write-edn! fs edn-path updated)
    (with-id (:id opts) updated)))

(defn list-instances
  [{:keys [fs root machine state]}]
  (let [dir   (str root "/foreman/" (name machine))
        names (or (when (fs/exists? fs dir) (fs/children fs dir)) [])
        ids   (->> names
                   (filter #(str/ends-with? % ".edn"))
                   (map #(subs % 0 (- (count %) 4)))
                   sort)]
    (cond->> (keep (fn [id]
                     (get-instance {:fs fs :root root :machine machine :id id}))
                   ids)
      state (filter #(= state (:state %)))
      true  vec)))
