(ns isaac.foreman.observer
  "Observer seam: registered observers see every handled transition.
   Unhandled events go to history, not observers."
  (:require
    [isaac.logger :as log]))

(defonce ^:private registry* (atom {}))

(defn register!
  "Register an observer fn under `id`. The fn receives a transition map."
  [id f]
  (swap! registry* assoc id f))

(defn clear!
  "Drop every registered observer. For test isolation."
  []
  (reset! registry* {}))

(defn- log-observer [event]
  (log/info :foreman/transition
            :machine (:machine event)
            :id      (:id event)
            :from    (:from event)
            :to      (:to event)
            :event   (:event event)))

(defn- resolve-observer [id]
  (or (get @registry* id)
      (when (= :log id) log-observer)))

(defn notify!
  "Notify each of `(:observers event)` of a handled transition.
   Unhandled events (`:unhandled?` true) are ignored."
  [{:keys [observers unhandled?] :as event}]
  (when-not unhandled?
    (doseq [id observers]
      (when-let [f (resolve-observer id)]
        (f event)))))
