(ns isaac.foreman.turn-observer
  (:require
    [isaac.foundation.config.loader :as loader]
    [isaac.agent.drive.observer :as drive-observer]
    [isaac.foreman.core :as core]
    [isaac.foundation.fs :as fs]))

(defn emit! [machine instance event ctx]
  (core/signal! {:root (loader/root) :fs (fs/instance)
                 :machine machine :id instance :event event :source :observer
                 :session (:session-key ctx) :request-id (:request-id ctx)}))

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
          (when (and (= :ok outcome) output-key)
            (store-output! machine instance output-key (:content ctx)))
          (when (or (not= :ok outcome) (not signaled?))
            (emit! machine instance (if (= :ok outcome) :foreman/turn-ended :foreman/turn-failed) ctx))))
      (on-turn-died [_ ctx _reason]
        (swap! signaled* disj [machine instance (:request-id ctx)])
        (emit! machine instance :foreman/turn-died ctx)))))

(defn register! []
  (drive-observer/register! :foreman factory))
