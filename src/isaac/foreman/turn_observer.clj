(ns isaac.foreman.turn-observer
  (:require
    [isaac.agent.drive.observer :as drive-observer]
    [isaac.foreman.core :as core]
    [isaac.foreman.machine :as machine]
    [isaac.foundation.config.loader :as loader]
    [isaac.foundation.fs :as fs]
    [isaac.foundation.logger :as log]))

(defn emit! [machine instance event ctx]
  (core/signal! {:root (loader/root) :fs (fs/instance)
                 :machine machine :id instance :event event
                 :session (:session-key ctx) :request-id (:request-id ctx)
                 :source (or (:source ctx) :observer)
                 :data (when-let [reason (:reason ctx)] {:reason reason})}))

(defonce ^:private signaled* (atom #{}))

(defn record-signal! [machine instance request-id]
  (when request-id
    (swap! signaled* conj [machine instance request-id])))

(defn store-output! [machine instance key content]
  (core/data! {:root (loader/root) :fs (fs/instance)
               :machine machine :id instance :set {key content}}))

(defn factory [[machine instance output-key]]
  (when (and machine instance)
    (reify drive-observer/TurnObserver
      (on-turn-started [_ ctx] (emit! machine instance :foreman/turn-started ctx))
      (on-turn-ended [_ ctx outcome]
        (let [identity [machine instance (:request-id ctx)]
              signaled? (and (:request-id ctx) (contains? @signaled* identity))]
          (swap! signaled* disj identity)
          (when (and (= :ok outcome) (keyword? output-key))
            (store-output! machine instance output-key (:content ctx)))
          (let [event-mode? (= :event (:mode output-key))
                {:keys [parsed valid? reason]} (when (and event-mode? (= :ok outcome))
                                                 (machine/parse-reply-event (:content ctx) (:valid-events output-key)))]
            (when event-mode?
              (log/info :foreman/reply-event :parsed parsed :valid? (boolean valid?)
                        :machine machine :instance instance :action (:action output-key)))
            (cond
              (not= :ok outcome) (emit! machine instance :foreman/turn-failed ctx)
              signaled? nil
              valid? (emit! machine instance parsed (assoc ctx :reason reason :source :reply))
              :else (emit! machine instance :foreman/turn-ended ctx)))))
      (on-turn-died [_ ctx _reason]
        (swap! signaled* disj [machine instance (:request-id ctx)])
        (emit! machine instance :foreman/turn-died ctx)))))

(defn register! []
  (drive-observer/register! :foreman factory))
