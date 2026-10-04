(ns isaac.foreman.action-spec
  (:require
    [isaac.foreman.action :as sut]
    [isaac.foundation.nexus :as nexus]
    [speclj.core :refer :all]))

(describe "Foreman action berth"
  (around [example]
    (nexus/-with-nested-nexus {}
      (example)))

  (it "installs an action contribution and runs it with machine, instance data and event"
    (let [received (atom nil)
          spec {:type :chime :times 3}
          ctx {:machine :lighthouse-watch :instance :beacon-7 :data {:tide :high} :event :dusk}]
      (with-redefs [isaac.foundation.module.lifecycle/resolve-symbol!
                    (fn [_] (fn [context action-spec]
                              (reset! received [context action-spec])
                              {:data {:rang (:times action-spec)}}))]
        (sut/register! [:chime {:handler 'marigold.chime/ring}]))
      (should= {:data {:rang 3}} (sut/run! :chime ctx spec))
      (should= [ctx spec] @received)))

  (it "refuses an unregistered action instead of silently queuing it"
    (should-throw clojure.lang.ExceptionInfo (sut/run! :gong {} {:type :gong}))))
