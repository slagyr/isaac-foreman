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

(defn factory [[machine instance]]
  (when (and machine instance)
    (reify drive-observer/TurnObserver
      (on-turn-started [_ ctx] (emit! machine instance :turn-started ctx))
      (on-turn-ended [_ ctx outcome]
        (emit! machine instance (if (= :ok outcome) :turn-ended :turn-failed) ctx))
      (on-turn-died [_ ctx _reason] (emit! machine instance :turn-died ctx)))))

(defn register! []
  (drive-observer/register! :foreman factory))
