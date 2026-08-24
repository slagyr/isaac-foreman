(ns isaac.foreman.machine
  "Pure state-machine engine: parse, validate, and step. No I/O.")

(defn parse
  "Accept a machine table. Already-a-map tables pass through."
  [table]
  table)

(defn- action-names [machine]
  (set (keys (:actions machine))))

(defn- transition-actions [row]
  (let [action (:action row)]
    (cond
      (nil? action)    []
      (sequential? action) (vec action)
      :else            [action])))

(defn- duplicate-row-keys [transitions]
  (->> transitions
       (map (juxt :start :event))
       frequencies
       (keep (fn [[pair n]] (when (> n 1) pair)))
       vec))

(defn- dangling-actions [machine]
  (let [known (action-names machine)]
    (->> (:transitions machine)
         (mapcat transition-actions)
         (concat (mapcat :exit (vals (:states machine)))
                 (mapcat :entry (vals (:states machine))))
         (remove #(contains? known %))
         distinct
         vec)))

(defn validate
  "Return {:ok true} or {:ok false :errors [...]} for a machine table."
  [machine]
  (let [dangling  (dangling-actions machine)
        dups      (duplicate-row-keys (:transitions machine))
        errors    (cond-> []
                    (seq dangling) (into (map #(str "dangling action reference: " (name %)) dangling))
                    (seq dups)     (into (map (fn [[start event]]
                                                (str "duplicate row: " start " + " event))
                                              dups)))]
    (if (seq errors)
      {:ok false :errors errors}
      {:ok true})))

(defn- matching-row [machine state event]
  (let [rows     (or (:transitions machine) [])
        explicit (first (filter #(and (= state (:start %)) (= event (:event %))) rows))
        wildcard (first (filter #(and (= :* (:start %)) (= event (:event %))) rows))]
    (or explicit wildcard)))

(defn step
  "Advance `machine` from `state` on `event`.
   Returns {:state :actions :status} where :status is :handled or :unhandled."
  [machine state event]
  (if-let [row (matching-row machine state event)]
    (let [end        (:end row)
          exit-acts  (or (get-in machine [:states state :exit]) [])
          trans-acts (transition-actions row)
          entry-acts (or (get-in machine [:states end :entry]) [])]
      {:state   end
       :actions (vec (concat exit-acts trans-acts entry-acts))
       :status  :handled})
    {:state   state
     :actions []
     :status  :unhandled}))
