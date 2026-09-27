(ns isaac.foreman.module
  (:require
    [isaac.config.root :as root]
    [isaac.foreman.core :as core]
    [isaac.foreman.turn-observer :as turn-observer]
    [isaac.fs :as fs]
    [isaac.module.protocol :as module]))

(defn create-module []
  (module/module {:on-load (fn [_]
                             (turn-observer/register!)
                             (core/resume! {:root (root/current-root) :fs (fs/instance)}))}))
