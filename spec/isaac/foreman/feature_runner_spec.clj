(ns isaac.foreman.feature-runner-spec
  (:require
    [gherclj.main :as gherclj]
    [isaac.foreman.feature-runner :as sut]
    [speclj.core :refer :all]))

(describe "Foreman feature runner"
  (it "releases agent threads after the scenarios finish so CI can exit promptly"
    (let [calls (atom [])]
      (with-redefs [gherclj/-main (fn [& args] (swap! calls conj [:features args]))
                    clojure.core/shutdown-agents (fn [] (swap! calls conj :shutdown))]
        (sut/-main "features/foreman/events.feature")
        (should= [[:features '("features/foreman/events.feature")] :shutdown] @calls))))

  (it "releases agent threads even when a scenario fails"
    (let [calls (atom [])]
      (with-redefs [gherclj/-main (fn [& _] (throw (ex-info "scenario failed" {})))
                    clojure.core/shutdown-agents (fn [] (swap! calls conj :shutdown))]
        (should-throw clojure.lang.ExceptionInfo (sut/-main))
        (should= [:shutdown] @calls))))
  )
