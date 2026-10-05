(ns isaac.foreman.machine
  "Pure state-machine engine: parse, validate, and step. No I/O."
  (:require
    [clojure.string :as str]
    [isaac.foundation.template :as template]))

(defn parse
  "Accept a machine table. Already-a-map tables pass through."
  [table]
  table)

(defn- action-names [machine]
  (set (keys (:actions machine))))

(defn- transition-actions [row]
  (let [actions (:actions row)]
    (if (sequential? actions) (vec actions) [])))

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

(defn reply-events [machine state]
  (->> (:transitions machine)
       (filter #(or (= state (:start %)) (= :* (:start %))))
       (map :event)
       (remove #(= "foreman" (namespace %)))
       distinct
       vec))

(defn parse-reply-event [content valid-events]
  (let [line (some-> content str/trim str/split-lines last)
        [_ raw reason] (when line (re-matches #"(?i)event:\s*([^\s]+)(?:\s+(.+))?" (str/trim line)))
        event (when raw (keyword raw))]
    {:parsed event :valid? (boolean (some #{event} valid-events))
     :reason (some-> reason str/trim)}))

(defn resolve-action
  "Look up action-name in the machine's own :actions, falling back to the
   shared :foreman :actions pool."
  [machine action-name shared]
  (or (get-in machine [:actions action-name])
      (get shared action-name)))

(defn render-action
  "Render all string leaves of an action using the entered state and event.
   Instance data is read at execution time, not from the event payload."
  [spec machine id state event data]
  (template/render-all spec {:machine (name machine) :instance (name id)
                             :state (name state) :event (name event) :data data}
                       {:on-missing :empty}))

(defn refusal-message
  "The message recorded (and thrown) when a signal has no matching transition."
  [event state]
  (str "no transition for " (name event) " from " (name state)))
