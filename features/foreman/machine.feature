Feature: Foreman — machines
  Orchestrations are state machines defined as config entities: root
  :machines in isaac.edn AND config/machines/<name>.edn both valid.
  Rows use SM terminology ({:start :event :end :actions}), where :actions
  is always a vector of action names; :initial names
  the birth state; :* matches any start state (explicit rows win); the
  optional :states map declares :entry/:exit actions. Actions are NAMED
  in the machine's :actions map (shared pool at :foreman {:actions}).

  Background:
    Given an Isaac root at "isaac-state"

    Scenario: machines validate from both forms; dangling references are rejected
    Given config file "isaac.edn" containing:
      """
      {:machines
       {"lighthouse-watch"
        {:initial :dark
         :actions {:light-lamp {:type :log :message "lamp lit"}}
         :transitions [{:start :dark :event :dusk :end :lit :actions [:light-lamp]}
                       {:start :lit  :event :dawn :end :dark}]}}}
      """
    And a file "config/machines/harbor-run.edn" exists with content:
      """
      {:initial :moored
       :transitions [{:start :moored :event :cast-off :end :sailing}]}
      """
    When isaac is run with "config validate"
    Then the exit code is 0
    Given config file "isaac.edn" containing:
      """
      {:machines
       {"ghost-ship"
        {:initial :adrift
         :transitions [{:start :adrift :event :storm :end :sunk :actions [:sound-alarm]}]}}}
      """
    When isaac is run with "config validate"
    Then the stderr contains "ghost-ship"
    And the stderr contains "sound-alarm"
    And the exit code is 1

    Scenario: exit, transition, and entry actions fire in order
    Given config file "isaac.edn" containing:
      """
      {:machines
       {"lighthouse-watch"
        {:initial :dark
         :actions {:strike-match {:type :log :message "match struck"}
                   :light-lamp   {:type :log :message "lamp lit"}
                   :trim-wick    {:type :log :message "wick trimmed"}}
         :states {:dark {:exit  [:strike-match]}
                  :lit  {:entry [:trim-wick]}}
         :transitions [{:start :dark :event :dusk :end :lit :actions [:light-lamp]}]}}}
      """
    When isaac is run with "foreman start lighthouse-watch beacon-7"
    When isaac is run with "foreman signal lighthouse-watch beacon-7 dusk"
    Then the stdout matches:
      | pattern                                  |
      | (?s)match struck.*lamp lit.*wick trimmed |
    And the exit code is 0
    When isaac is run with "foreman status lighthouse-watch beacon-7"
    Then the stdout matches:
      | pattern                                                    |
      | (?s)strike-match.*light-lamp.*trim-wick.*dusk: dark -> lit |

    Scenario: wildcard transitions fire from any state; instances are isolated
    Given config file "isaac.edn" containing:
      """
      {:machines
       {"lighthouse-watch"
        {:initial :dark
         :actions {:take-shelter {:type :log :message "keeper shelters"}}
         :transitions [{:start :dark :event :dusk  :end :lit}
                       {:start :*    :event :storm :end :sheltered :actions [:take-shelter]}]}}}
      """
    When isaac is run with "foreman start lighthouse-watch beacon-7"
    When isaac is run with "foreman start lighthouse-watch beacon-9"
    When isaac is run with "foreman signal lighthouse-watch beacon-9 dusk"
    When isaac is run with "foreman signal lighthouse-watch beacon-7 storm"
    Then the stdout contains "beacon-7: dark -> sheltered (storm)"
    When isaac is run with "foreman signal lighthouse-watch beacon-9 storm"
    Then the stdout contains "beacon-9: lit -> sheltered (storm)"
    And the exit code is 0
    When isaac is run with "foreman status lighthouse-watch beacon-9"
    Then the stdout matches:
      | pattern                                        |
      | (?s)dusk: dark -> lit.*storm: lit -> sheltered |

    Scenario: a row names its actions as a vector under :actions (isaac-50zy)
    Given config file "isaac.edn" containing:
      """
      {:machines
       {"lighthouse-watch"
        {:initial :dark
         :actions {:light-lamp {:type :log :message "lamp lit"}}
         :transitions [{:start :dark :event :dusk :end :lit :action [:light-lamp]}]}}}
      """
    When isaac is run with "config validate"
    Then the stderr contains "lighthouse-watch"
    And the stderr contains "action"
    And the exit code is 1
    Given config file "isaac.edn" containing:
      """
      {:machines
       {"lighthouse-watch"
        {:initial :dark
         :actions {:light-lamp {:type :log :message "lamp lit"}}
         :transitions [{:start :dark :event :dusk :end :lit :actions :light-lamp}]}}}
      """
    When isaac is run with "config validate"
    Then the stderr contains "lighthouse-watch"
    And the stderr contains "actions"
    And the exit code is 1
