Feature: Foreman — the template contract (isaac-wh2o)
  Every string in an action's spec is a template: prompts, :frequencies,
  :command, :cwd, a :log :message, resource-pool names. The variables are
  fixed and documented: {{machine}}, {{instance}}, {{state}} (the state
  just entered), {{event}} (the event that fired the transition) and
  {{data.<path>}} (the instance data). A {{data.…}} key that is absent at
  run time fills empty, because data arrives over time; any other name is
  a typo, rejected by `config validate`. Rendering uses foundation's
  shared template engine.

  Background:
    Given an Isaac root at "target/test-state"
    And default Grover setup

  @wip
  Scenario: state and event are template variables
    Given the isaac file "config/machines/lighthouse-watch.edn" exists with:
      """
      {:initial :dark
       :actions {:note {:type :log :message "{{machine}}/{{instance}} entered {{state}} on {{event}}"}}
       :transitions [{:start :dark :event :dusk :end :lit :actions [:note]}]}
      """
    When isaac is run with "foreman start lighthouse-watch beacon-7"
    And isaac is run with "foreman signal lighthouse-watch beacon-7 dusk"
    Then the stdout contains "lighthouse-watch/beacon-7 entered lit on dusk"
    And the exit code is 0

  @wip
  Scenario: a log message reads the instance data like any other string
    Given the isaac file "config/machines/lighthouse-watch.edn" exists with:
      """
      {:initial :dark
       :actions {:note {:type :log :message "keeper {{data.keeper.name}} lit {{instance}}"}}
       :transitions [{:start :dark :event :dusk :end :lit :actions [:note]}]}
      """
    When isaac is run with "foreman start lighthouse-watch beacon-7 --data {:keeper {:name \"Atticus\"}}"
    And isaac is run with "foreman signal lighthouse-watch beacon-7 dusk"
    Then the stdout contains "keeper Atticus lit beacon-7"

  @wip
  Scenario: an unknown template variable is a config error
    Given the isaac file "config/machines/lighthouse-watch.edn" exists with:
      """
      {:initial :dark
       :actions {:note {:type :log :message "lit {{instnace}}"}}
       :transitions [{:start :dark :event :dusk :end :lit :actions [:note]}]}
      """
    When isaac is run with "config validate"
    Then the stderr matches:
      | pattern                                    |
      | machines\.lighthouse-watch\.actions\.note  |
      | instnace                                   |
    And the exit code is 1

  Scenario: an absent data key is not a config error
    Given the isaac file "config/machines/lighthouse-watch.edn" exists with:
      """
      {:initial :dark
       :actions {:note {:type :log :message "lit by {{data.keeper.name}}"}}
       :transitions [{:start :dark :event :dusk :end :lit :actions [:note]}]}
      """
    When isaac is run with "config validate"
    Then the exit code is 0
