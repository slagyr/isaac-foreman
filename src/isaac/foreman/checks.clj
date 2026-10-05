(ns isaac.foreman.checks
  "Post-load config checks for machine actions and template variables."
  (:require
    [isaac.foundation.template :as template]))

(defn- action-names [machine shared]
  (set (concat (keys (:actions machine))
               (keys shared))))

(defn- transition-actions [row]
  (let [actions (:actions row)]
    (if (sequential? actions) (vec actions) [])))

(defn- state-actions [machine]
  (mapcat (fn [decl]
            (concat (:exit decl) (:entry decl)))
          (vals (:states machine))))

(defn- dangling-actions [machine shared]
  (let [known (action-names machine shared)]
    (->> (concat (mapcat transition-actions (:transitions machine))
                 (state-actions machine))
         (remove #(contains? known %))
         distinct
         vec)))

(defn check-dangling-actions
  "Reject machines that name an action with no definition in the machine
   :actions map or the shared :foreman :actions pool."
  [{:keys [config]}]
  (let [shared (or (get-in config [:foreman :actions]) {})]
    {:errors   (vec
                 (mapcat
                   (fn [[machine-id machine]]
                     (for [action (dangling-actions machine shared)]
                       {:key   (str "machines." machine-id)
                        :value (str "dangling action reference: " (name action))}))
                   (or (:machines config) {})))
     :warnings []}))

(defn- raw-machines
  "Machine tables as written, before schema conform strips unrecognized keys
   (isaac-50zy: conform silently drops a legacy :action key, so detecting it
   requires the pre-conform data — result:root for isaac.edn's :machines,
   result:raw for config/machines/<id>.edn entity files)."
  [ctx]
  (merge (get-in ctx [:result :root :machines])
         (get-in ctx [:result :raw :machines])))

(defn- allowed-variable? [variable]
  (or (contains? #{"machine" "instance" "state" "event"} variable)
      (boolean (re-matches #"data\.[\w-]+(?:\.[\w-]+)*" variable))))

(defn- action-template-errors [prefix actions]
  (for [[action spec] actions
        variable (sort (template/placeholders spec))
        :when (not (allowed-variable? variable))]
    {:key (str prefix ".actions." (name action))
     :value (str "unknown template variable: " variable)}))

(defn check-template-variables
  "Reject unknown placeholders in every action spec, including nested strings."
  [{:keys [config] :as ctx}]
  (let [machines (merge (:machines config) (raw-machines ctx))
        shared (merge (get-in config [:foreman :actions])
                      (get-in ctx [:result :root :foreman :actions])
                      (get-in ctx [:result :raw :foreman :actions]))]
    {:errors (vec (concat (action-template-errors "foreman" shared)
                          (mapcat (fn [[machine-id machine]]
                                    (action-template-errors (str "machines." machine-id) (:actions machine)))
                                  machines)))
     :warnings []}))

(defn- legacy-action-errors [machine-id row]
  (when (contains? row :action)
    [{:key   (str "machines." machine-id)
      :value (str "transition row uses :action — rename to :actions: " (pr-str (:action row)))}]))

(defn check-actions-shape
  "Reject transition rows that still use :action — rows always name their
   actions as a vector under :actions. (A non-vector :actions is already
   rejected by the machine schema itself.)"
  [ctx]
  {:errors   (vec
               (mapcat
                 (fn [[machine-id machine]]
                   (mapcat #(legacy-action-errors machine-id %) (:transitions machine)))
                 (raw-machines ctx)))
   :warnings []})
