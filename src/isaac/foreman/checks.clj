(ns isaac.foreman.checks
  "Post-load config check: dangling action refs on every machine table.")

(defn- action-names [machine shared]
  (set (concat (keys (:actions machine))
               (keys shared))))

(defn- transition-actions [row]
  (let [action (:action row)]
    (cond
      (nil? action)        []
      (sequential? action) (vec action)
      :else                [action])))

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
