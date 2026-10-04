Feature: Foreman — contributed action types (isaac-np1m)
  Action types are a berth, :isaac.foreman/action, keyed by type. :log,
  :turn, :notify and :exec are Foreman's own contributions; a module can
  contribute more, and a machine uses them like any built-in. An action
  receives the machine, instance, its spec, and the instance data, and may
  return {:data {...}} to merge into the instance data before the next
  action runs, or {:failed {...}} to stop the list and fire
  :<action>-failed. The machine schema accepts only registered types, so
  a typo is a config error, not a silent no-op.

  New step: the "chime" action module is registered — a fixture module
  whose :chime action returns {:data {:rang <:times>}}.

  Background:
    Given an Isaac root at "target/test-state"
    And default Grover setup

  Scenario: a module-contributed action runs on a transition and writes the data
    Given the "chime" action module is registered
    And the isaac file "config/machines/lighthouse-watch.edn" exists with:
      """
      {:initial :dark
       :actions {:ring-bell {:type :chime :times 3}}
       :transitions [{:start :dark :event :dusk :end :lit :actions [:ring-bell]}]}
      """
    When isaac is run with "foreman start lighthouse-watch beacon-7"
    And isaac is run with "foreman signal lighthouse-watch beacon-7 dusk"
    Then the stdout contains "beacon-7: dark -> lit (dusk)"
    When isaac is run with "foreman data lighthouse-watch beacon-7"
    Then the stdout matches:
      | pattern      |
      | :rang\s+3    |

  Scenario: an action type nobody registered is a config error
    Given the isaac file "config/machines/lighthouse-watch.edn" exists with:
      """
      {:initial :dark
       :actions {:ring-bell {:type :gong}}
       :transitions [{:start :dark :event :dusk :end :lit :actions [:ring-bell]}]}
      """
    When isaac is run with "config validate"
    Then the stderr matches:
      | pattern                                       |
      | machines\.lighthouse-watch\.actions\.ring-bell |
      | gong                                          |
    And the exit code is 1
