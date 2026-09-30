(ns isaac.foreman.test-runner
  "`isaac foreman test <file.feature>…` — Foreman-provided Gherkin steps that
   walk the pure engine (isaac.foreman.machine/step). Nothing executes: no
   turn is submitted, no :log prints, no instance is created or persisted.

   Parsing reuses gherclj's own parser (gherclj.parser) as a library — see
   the bean note on that decision. Everything below the parse is Foreman's
   own pure step interpreter for the fixed vocabulary."
  (:require
    [clojure.edn :as edn]
    [clojure.string :as str]
    [gherclj.parser :as parser]
    [isaac.foreman.core :as core]
    [isaac.foreman.machine :as machine]
    [isaac.foundation.fs :as fs]))

;; ----- step text patterns -----

(def ^:private machine-re         #"^the \"([^\"]+)\" machine$")
(def ^:private instance-re        #"^instance \"([^\"]+)\"$")
(def ^:private state-re           #"^the state is \"([^\"]*)\"$")
(def ^:private signaled-re        #"^\"([^\"]+)\" is signaled$")
(def ^:private unhandled-re       #"^the signal is unhandled$")
(def ^:private actions-are-re     #"^the actions are \"([^\"]*)\"$")
(def ^:private actions-contain-re #"^the actions contain \"([^\"]+)\"$")
(def ^:private no-actions-re      #"^there are no actions$")
(def ^:private prompt-re          #"^the \"([^\"]+)\" prompt (is|contains|matches) \"(.*)\"$")
(def ^:private target-re          #"^the \"([^\"]+)\" target is:$")

;; ----- IR shaping -----

(defn resolve-kinds
  "Resolve each step's effective Given/When/Then kind, carrying And/But
   forward from the previous step. A leading And/But (malformed input)
   defaults to :given."
  [steps]
  (loop [steps steps last-kind :given acc []]
    (if (empty? steps)
      acc
      (let [{:keys [type] :as step} (first steps)
            kind (if (#{:and :but} type) last-kind type)]
        (recur (rest steps) kind (conj acc (assoc step :kind kind)))))))

(defn- display-line [{:keys [type text]}]
  (str (str/capitalize (name type)) " " text))

(defn- table-rows
  "gherclj's table IR splits the first physical row into :headers and the
   rest into :rows. Foreman's `target is:` table has no header row — every
   physical row is a key/value pair — so headers is just the first row."
  [table]
  (into [(:headers table)] (:rows table)))

(defn- parse-cell
  "A table cell is EDN when it reads as EDN and isn't a bare symbol (a bare
   word like `bartholomew` reads as a symbol, not data) — otherwise it's a
   plain string."
  [s]
  (let [s (str/trim s)]
    (try
      (let [v (edn/read-string s)]
        (if (symbol? v) s v))
      (catch Exception _ s))))

(defn- expected-target [table]
  (into {} (map (fn [[k v]] [(keyword k) (parse-cell v)])) (table-rows table)))

(defn- actual-target [ctx action-name]
  (let [spec (machine/resolve-action (:table ctx) action-name (:shared ctx))]
    (cond-> (or (:frequencies spec) {})
      (:resource-pools spec) (assoc :resource-pools (:resource-pools spec)))))

;; ----- scenario context -----

(defn- fresh-ctx [cfg]
  {:cfg cfg :machine nil :table nil :shared nil :id "test-1" :state nil :last nil :fail nil})

(defn- fail [ctx step reason]
  (assoc ctx :fail {:step (display-line step) :reason reason}))

(defn- action-spec [ctx action-name]
  (or (machine/resolve-action (:table ctx) action-name (:shared ctx))
      (throw (ex-info (str "unknown action: " (name action-name)) {}))))

(defn- filled-prompt [ctx action-name]
  (let [spec (action-spec ctx action-name)]
    (machine/fill-prompt (:prompt spec) (:machine ctx) (:id ctx) {})))

;; ----- build steps (Given) -----

(defmulti ^:private exec-given (fn [_ctx text] (cond
                                                  (re-matches machine-re text)  :machine
                                                  (re-matches instance-re text) :instance
                                                  (re-matches state-re text)    :state
                                                  :else                         :unknown)))

(defmethod exec-given :machine [ctx text]
  (let [[_ name] (re-matches machine-re text)
        table    (core/machine-table (:cfg ctx) name)]
    (if-not table
      [ctx (str "unknown machine: " name)]
      [(assoc ctx :machine name :table table :shared (get-in (:cfg ctx) [:foreman :actions])
                  :state (:initial table))
       nil])))

(defmethod exec-given :instance [ctx text]
  (let [[_ id] (re-matches instance-re text)]
    [(assoc ctx :id id) nil]))

(defmethod exec-given :state [ctx text]
  (let [[_ state] (re-matches state-re text)]
    [(assoc ctx :state (keyword state)) nil]))

(defmethod exec-given :unknown [_ctx text]
  [nil (str "unrecognized step: Given " text)])

;; ----- operate steps (When) -----

(defn- exec-when [ctx text]
  (if-let [[_ event] (re-matches signaled-re text)]
    (if-not (:table ctx)
      [ctx "no machine given"]
      (let [event-kw (keyword event)
            result   (machine/step (:table ctx) (:state ctx) event-kw)]
        [(assoc ctx :state (:state result)
                    :last {:event event-kw :status (:status result) :actions (:actions result)
                           :acknowledged? false})
         nil]))
    [nil (str "unrecognized step: When " text)]))

;; ----- check steps (Then) -----

(defn- unhandled-gate
  "An unhandled signal fails every check except `the signal is unhandled`,
   until that check acknowledges it. Returns a mismatch string, or nil when
   the check may proceed."
  [ctx acknowledging?]
  (let [last (:last ctx)]
    (when (and last (= :unhandled (:status last)) (not (:acknowledged? last)) (not acknowledging?))
      (machine/refusal-message (:event last) (:state ctx)))))

(defn- check-state [ctx expected]
  (let [actual (name (:state ctx))]
    (if (= actual expected)
      [ctx nil]
      [ctx (str "expected " expected ", got " actual)])))

(defn- check-unhandled [ctx]
  (let [last (:last ctx)]
    (if (and last (= :unhandled (:status last)))
      [(assoc ctx :last (assoc last :acknowledged? true)) nil]
      [ctx (str "expected the signal to be unhandled, got " (if last (name (:status last)) "no signal"))])))

(defn- check-actions-are [ctx expected]
  (let [wanted (if (str/blank? expected) [] (mapv str/trim (str/split expected #",")))
        actual (mapv name (:actions (:last ctx)))]
    (if (= wanted actual)
      [ctx nil]
      [ctx (str "expected actions " (pr-str wanted) ", got " (pr-str actual))])))

(defn- check-actions-contain [ctx expected]
  (let [actual (mapv name (:actions (:last ctx)))]
    (if (some #(= expected %) actual)
      [ctx nil]
      [ctx (str "expected actions to contain " (pr-str expected) ", got " (pr-str actual))])))

(defn- check-no-actions [ctx]
  (let [actual (:actions (:last ctx))]
    (if (empty? actual)
      [ctx nil]
      [ctx (str "expected no actions, got " (pr-str (mapv name actual)))])))

(defn- check-prompt [ctx action mode expected]
  (try
    (let [filled (filled-prompt ctx (keyword action))]
      (if (case mode
            "is"       (= filled expected)
            "contains" (str/includes? filled expected)
            "matches"  (boolean (re-find (re-pattern expected) filled)))
        [ctx nil]
        [ctx (str "expected prompt " mode " " (pr-str expected) ", got " (pr-str filled))]))
    (catch Exception e
      [ctx (ex-message e)])))

(defn- check-target [ctx action table]
  (try
    (let [expected (expected-target table)
          actual   (actual-target ctx (keyword action))]
      (if (= expected actual)
        [ctx nil]
        [ctx (str "expected target " (pr-str expected) ", got " (pr-str actual))]))
    (catch Exception e
      [ctx (ex-message e)])))

(defn- exec-then [ctx {:keys [text table]}]
  (cond
    (re-matches unhandled-re text)
    (check-unhandled ctx)

    :else
    (if-let [gate (unhandled-gate ctx false)]
      [ctx gate]
      (cond
        (re-matches state-re text)
        (let [[_ expected] (re-matches state-re text)] (check-state ctx expected))

        (re-matches actions-are-re text)
        (let [[_ expected] (re-matches actions-are-re text)] (check-actions-are ctx expected))

        (re-matches actions-contain-re text)
        (let [[_ expected] (re-matches actions-contain-re text)] (check-actions-contain ctx expected))

        (re-matches no-actions-re text)
        (check-no-actions ctx)

        (re-matches prompt-re text)
        (let [[_ action mode expected] (re-matches prompt-re text)] (check-prompt ctx action mode expected))

        (re-matches target-re text)
        (let [[_ action] (re-matches target-re text)] (check-target ctx action table))

        :else
        [ctx (str "unrecognized step: Then " text)]))))

;; ----- driving one scenario -----

(defn- exec-step [ctx step]
  (if (:fail ctx)
    ctx
    (try
      (let [[ctx' reason] (case (:kind step)
                             :given (exec-given ctx (:text step))
                             :when  (exec-when ctx (:text step))
                             :then  (exec-then ctx step)
                             [ctx (str "unrecognized step: " (display-line step))])]
        (if reason
          (fail ctx step reason)
          ctx'))
      (catch Exception e
        (fail ctx step (ex-message e))))))

(defn run-scenario
  "Run one scenario's background + own steps against `cfg`. Returns
   {:name :pass? :step :mismatch}."
  [cfg background-steps scenario]
  (let [steps (concat (resolve-kinds background-steps) (resolve-kinds (:steps scenario)))
        ctx   (reduce exec-step (fresh-ctx cfg) steps)]
    (if-let [{:keys [step reason]} (:fail ctx)]
      {:name (:scenario scenario) :pass? false :step step :mismatch reason}
      {:name (:scenario scenario) :pass? true})))

(defn run-scenarios
  "Run every scenario in one or more parsed gherclj feature IRs against
   `cfg`. Pure — no I/O."
  [cfg feature-irs]
  (mapcat (fn [{:keys [background scenarios]}]
            (map #(run-scenario cfg (:steps background) %) scenarios))
          feature-irs))

;; ----- formatting -----

(defn format-result [{:keys [name pass? step mismatch]}]
  (if pass?
    (str "PASS " name)
    (str "FAIL " name " — " step ": " mismatch)))

(defn format-results
  "Returns {:lines [...] :exit 0|1}."
  [results]
  {:lines (mapv format-result results)
   :exit  (if (every? :pass? results) 0 1)})

;; ----- file I/O glue -----

(defn- resolve-path [root path]
  (if (str/starts-with? path "/") path (str root "/" path)))

(defn- parse-file [root fs* path]
  (let [full (resolve-path root path)]
    (when-not (fs/exists? fs* full)
      (throw (ex-info (str "no such file: " path) {})))
    (parser/parse-feature (fs/slurp fs* full))))

(defn run-files!
  "Parse and run every scenario in `paths` (resolved against root), print one
   PASS/FAIL line per scenario, and return the process exit code."
  [{:keys [root fs]} paths]
  (let [fs*     (or fs (fs/instance) (fs/real-fs))
        cfg     (core/load-cfg {:root root :fs fs*})
        irs     (mapv #(parse-file root fs* %) paths)
        results (run-scenarios cfg irs)
        {:keys [lines exit]} (format-results results)]
    (doseq [line lines] (println line))
    exit))
