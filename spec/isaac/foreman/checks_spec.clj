(ns isaac.foreman.checks-spec
  (:require
    [isaac.foreman.checks :as sut]
    [speclj.core :refer :all]))

(describe "isaac.foreman.checks"

  (it "accepts a machine whose actions are defined locally"
    (let [result (sut/check-dangling-actions
                   {:config {:machines {"lighthouse-watch"
                                        {:actions     {:light-lamp {:type :log}}
                                         :transitions [{:start :dark :event :dusk :end :lit
                                                        :action [:light-lamp]}]}}}})]
      (should= [] (:errors result))))

  (it "rejects a dangling action and names the machine plus the action"
    (let [result (sut/check-dangling-actions
                   {:config {:machines {"ghost-ship"
                                        {:transitions [{:start :adrift :event :storm :end :sunk
                                                        :action [:sound-alarm]}]}}}})
          err    (first (:errors result))]
      (should= 1 (count (:errors result)))
      (should (re-find #"ghost-ship" (str (:key err) " " (:value err))))
      (should (re-find #"sound-alarm" (str (:value err))))))

  (it "resolves actions from the shared :foreman pool"
    (let [result (sut/check-dangling-actions
                   {:config {:foreman  {:actions {:sound-alarm {:type :log}}}
                             :machines {"ghost-ship"
                                        {:transitions [{:start :adrift :event :storm :end :sunk
                                                        :action [:sound-alarm]}]}}}})]
      (should= [] (:errors result))))

  )
