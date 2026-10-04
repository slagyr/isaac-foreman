(ns isaac.foreman-action-steps
  (:require
    [gherclj.core :as g :refer [defgiven helper!]]
    [isaac.foundation.module.discovery :as discovery]
    [isaac.foundation.cli-steps :as cli-steps]))

(helper! isaac.foreman-action-steps)

(defn register-chime! []
  (g/assoc! :chime-action? true))

(defgiven "the \"chime\" action module is registered" isaac.foreman-action-steps/register-chime!)

(defn chime [ctx spec]
  {:data {:rang (:times spec)}})

(declare fixture-index)
(def builtin-index discovery/builtin-index)

(cli-steps/register-isaac-run-wrapper!
  (fn [thunk]
    (if (g/get :chime-action?)
      (with-redefs [discovery/builtin-index fixture-index]
        (thunk))
      (thunk))))

(defn fixture-index []
  (assoc (builtin-index) :marigold.chime
         {:coord {}
          :manifest {:id :marigold.chime
                     :version "0.1.0"
                     :factory 'isaac.foundation.module.protocol/module
                     :isaac.foreman/action {:chime {:handler 'isaac.foreman-action-steps/chime
                                                    :action-spec {:times {:type :int}}}}}}))
