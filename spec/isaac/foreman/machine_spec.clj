(ns isaac.foreman.machine-spec
  (:require
    [clojure.string :as str]
    [isaac.foreman.machine :as sut]
    [speclj.core :refer :all]))

(def lighthouse
  {:initial     :dark
   :actions     {:light-lamp {:type :log :message "lamp lit"}
                 :douse-lamp {:type :log :message "lamp doused"}}
   :transitions [{:start :dark :event :dusk :end :lit :action [:light-lamp]}
                 {:start :lit :event :dawn :end :dark :action [:douse-lamp]}]})

(describe "isaac.foreman.machine"

  (context "parse"

    (it "returns the machine table unchanged when already a map"
      (should= lighthouse (sut/parse lighthouse)))

    )

  (context "validate"

    (it "accepts a well-formed machine"
      (should= {:ok true} (sut/validate lighthouse)))

    (it "rejects a dangling action reference"
      (let [result (sut/validate {:initial     :adrift
                                  :transitions [{:start :adrift :event :storm :end :sunk :action [:sound-alarm]}]})]
        (should-not (:ok result))
        (should (some #(re-find #"sound-alarm" (str %)) (:errors result)))))

    (it "rejects a duplicate start+event row"
      (let [result (sut/validate {:initial     :dark
                                  :transitions [{:start :dark :event :dusk :end :lit}
                                                {:start :dark :event :dusk :end :other}]})]
        (should-not (:ok result))
        (should (some #(re-find #"duplicate" (str/lower-case (str %))) (:errors result)))))

    )

  (context "step"

    (it "moves dark + dusk to lit and emits the transition action"
      (let [result (sut/step lighthouse :dark :dusk)]
        (should= :lit (:state result))
        (should= [:light-lamp] (:actions result))
        (should= :handled (:status result))))

    (it "returns unhandled without changing state when no row matches"
      (let [result (sut/step lighthouse :dark :earthquake)]
        (should= :dark (:state result))
        (should= [] (:actions result))
        (should= :unhandled (:status result))))

    (it "fires exit, transition, then entry actions in that order"
      (let [machine {:initial     :dark
                     :actions     {:strike-match {:type :log :message "match struck"}
                                   :light-lamp   {:type :log :message "lamp lit"}
                                   :trim-wick    {:type :log :message "wick trimmed"}}
                     :states      {:dark {:exit [:strike-match]}
                                   :lit  {:entry [:trim-wick]}}
                     :transitions [{:start :dark :event :dusk :end :lit :action [:light-lamp]}]}
            result  (sut/step machine :dark :dusk)]
        (should= :lit (:state result))
        (should= [:strike-match :light-lamp :trim-wick] (:actions result))))

    (it "lets an explicit row beat a wildcard for the same event"
      (let [machine {:initial     :dark
                     :transitions [{:start :dark :event :storm :end :battened}
                                   {:start :* :event :storm :end :sheltered}]}
            from-dark (sut/step machine :dark :storm)
            from-lit  (sut/step machine :lit :storm)]
        (should= :battened (:state from-dark))
        (should= :sheltered (:state from-lit))))

    (it "matches a wildcard from any origin state"
      (let [machine {:initial     :dark
                     :actions     {:take-shelter {:type :log :message "keeper shelters"}}
                     :transitions [{:start :dark :event :dusk :end :lit}
                                   {:start :* :event :storm :end :sheltered :action [:take-shelter]}]}
            from-dark (sut/step machine :dark :storm)
            from-lit  (sut/step machine :lit :storm)]
        (should= :sheltered (:state from-dark))
        (should= [:take-shelter] (:actions from-dark))
        (should= :sheltered (:state from-lit))
        (should= [:take-shelter] (:actions from-lit))))

    )

  )
