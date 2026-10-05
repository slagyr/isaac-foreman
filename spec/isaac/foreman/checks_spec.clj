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
                                                        :actions [:light-lamp]}]}}}})]
      (should= [] (:errors result))))

  (it "rejects a dangling action and names the machine plus the action"
    (let [result (sut/check-dangling-actions
                   {:config {:machines {"ghost-ship"
                                        {:transitions [{:start :adrift :event :storm :end :sunk
                                                        :actions [:sound-alarm]}]}}}})
          err    (first (:errors result))]
      (should= 1 (count (:errors result)))
      (should (re-find #"ghost-ship" (str (:key err) " " (:value err))))
      (should (re-find #"sound-alarm" (str (:value err))))))

  (it "resolves actions from the shared :foreman pool"
    (let [result (sut/check-dangling-actions
                   {:config {:foreman  {:actions {:sound-alarm {:type :log}}}
                             :machines {"ghost-ship"
                                        {:transitions [{:start :adrift :event :storm :end :sunk
                                                        :actions [:sound-alarm]}]}}}})]
      (should= [] (:errors result))))

  (context "template variables"
    (it "rejects unknown placeholders in local and shared action strings, naming the machine and action"
      (let [local {:actions {:note {:type :log :message "{{instnace}}"}}}
            result (sut/check-template-variables
                     {:config {:foreman {:actions {:report {:type :log :message "{{unknown}}"}}}}
                      :result {:raw {:machines {"lighthouse-watch" local}}}})]
        (should= 2 (count (:errors result)))
        (should (some #(and (re-find #"machines.lighthouse-watch.actions.note" (:key %))
                           (re-find #"instnace" (:value %))) (:errors result)))
        (should (some #(and (re-find #"foreman.actions.report" (:key %))
                           (re-find #"unknown" (:value %))) (:errors result)))))

    (it "accepts the fixed variables and a missing data path in nested values"
      (should= [] (:errors (sut/check-template-variables
                             {:result {:raw {:machines {"lighthouse-watch"
                                                      {:actions {:note {:type :turn
                                                                        :prompt "{{machine}} {{instance}} {{state}} {{event}}"
                                                                        :frequencies {:session ["{{data.keeper.name}}"]}}}}}}}})))))

  (context "check-actions-shape"

    (it "accepts a row whose actions are a vector under :actions"
      (let [result (sut/check-actions-shape
                     {:result {:root {:machines {"lighthouse-watch"
                                                 {:transitions [{:start :dark :event :dusk :end :lit
                                                                 :actions [:light-lamp]}]}}}}})]
        (should= [] (:errors result))))

    (it "rejects a row that still uses :action, naming the machine and the key"
      (let [result (sut/check-actions-shape
                     {:result {:root {:machines {"lighthouse-watch"
                                                 {:transitions [{:start :dark :event :dusk :end :lit
                                                                 :action [:light-lamp]}]}}}}})
            err    (first (:errors result))]
        (should= 1 (count (:errors result)))
        (should (re-find #"lighthouse-watch" (str (:key err) " " (:value err))))
        (should (re-find #"action" (str (:value err))))))

    (it "checks rows loaded from a config/machines/<id>.edn entity file too"
      (let [result (sut/check-actions-shape
                     {:result {:raw {:machines {"harbor-run"
                                                {:transitions [{:start :moored :event :cast-off :end :sailing
                                                                :action [:log-it]}]}}}}})
            err    (first (:errors result))]
        (should= 1 (count (:errors result)))
        (should (re-find #"harbor-run" (str (:key err))))))

    )

  )
