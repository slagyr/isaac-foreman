(ns isaac.foreman.action
  "Action implementations contributed to :isaac.foreman/action. The module index
   is the source of truth for config validation; the nexus holds live handlers."
  (:refer-clojure :exclude [run!])
  (:require
    [isaac.foreman.exec :as exec]
    [isaac.foundation.module.berths :as berths]
    [isaac.foundation.module.lifecycle :as lifecycle]
    [isaac.foundation.nexus :as nexus]))

(defn log! [_ spec]
  (when-let [message (:message spec)] (println message)))

(defn turn! [_ _]
  {:pending? true})

(defn notify! [_ _]
  {:pending? true})

(defn exec! [{:keys [action machine instance data]} spec]
  (let [{:keys [output failure]} (exec/run! action spec machine instance data)]
    (if failure
      {:failed failure}
      (if-let [into (get-in spec [:output :data])]
        {:data {into output}}
        (do (println (str (name action) " (exec): " (pr-str output))) nil)))))

(defn register! [[type {:keys [handler]}]]
  (nexus/register! [:isaac.foreman/actions type]
                   (lifecycle/resolve-symbol! handler)))

(defn run! [type ctx spec]
  (when-not (nexus/get-in [:isaac.foreman/actions type])
    (berths/register-builtin-berth-entry! :isaac.foreman/action type))
  (if-let [handler (nexus/get-in [:isaac.foreman/actions type])]
    (handler ctx spec)
    (throw (ex-info (str "unregistered Foreman action type: " type) {:type type}))))
