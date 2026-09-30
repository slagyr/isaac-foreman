(ns isaac.foreman.module
  (:require
    [isaac.foundation.config.loader :as loader]
    [isaac.foreman.core :as core]
    [isaac.foreman.turn-observer :as turn-observer]
    [isaac.foundation.fs :as fs]
    [isaac.foundation.module.protocol :as module]))

(defn create-module []
  (module/module {:on-load (fn [_]
                             (turn-observer/register!)
                             (core/resume! {:root (loader/root) :fs (fs/instance)}))}))
