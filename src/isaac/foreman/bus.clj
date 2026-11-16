(ns isaac.foreman.bus
  "In-process fan-out of records after durable Foreman writes."
  (:require
    [isaac.foundation.logger :as log]))

(defonce ^:private subscribers (atom {}))

(defn subscribe!
  "Register a watcher; returns an idempotent unsubscribe function."
  [watcher]
  (let [id (Object.)]
    (swap! subscribers assoc id watcher)
    #(swap! subscribers dissoc id)))

(defn publish! [message]
  (doseq [[id watcher] @subscribers]
    (try
      (watcher message)
      (catch Exception e
        (swap! subscribers dissoc id)
        (log/error :foreman.stream/error :machine (:machine message) :cause (str e)))))
  nil)
