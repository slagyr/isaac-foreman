(ns isaac.foreman.module
  (:require
    [isaac.config.loader :as loader]
    [isaac.foreman.core :as core]
    [isaac.foreman.turn-observer :as turn-observer]
    [isaac.fs :as fs]
    [isaac.module.protocol :as module]))

(defn create-module []
  (module/module {:on-load (fn [_]
                             (turn-observer/register!)
                             (core/resume! {:root (loader/root) :fs (fs/instance)}))}))
