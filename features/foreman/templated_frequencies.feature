Feature: Foreman — templated frequencies (isaac-8r7m)
  String values in a :turn action's :frequencies are templated like its
  prompt ({{machine}}, {{instance}}, {{data.a.b}}), so one machine can give
  every instance its own session: {:session "bean-{{instance}}" :create
  :if-missing}. Machine tests see the rendered target.

  Background:
    Given an Isaac root at "target/test-state"
    And default Grover setup
    And the isaac EDN file "config/crew/bartholomew.edn" exists with:
      | path  | value  |
      | model | grover |
    And the isaac file "config/machines/lighthouse-watch.edn" exists with:
      """
      {:initial :dark
       :actions {:tend-lamp {:type        :turn
                             :frequencies {:crew "bartholomew" :session "lamp-{{instance}}" :create :if-missing}
                             :prompt      "Tend the lamp at {{instance}}."}}
       :transitions [{:start :dark :event :dusk :end :tending :actions [:tend-lamp]}]}
      """

  @wip
  Scenario: each instance's turn runs in its own templated session
    Given the following model responses are queued:
      | type | content        | model |
      | text | Lamp tended.   | echo  |
      | text | Lamp tended.   | echo  |
    When isaac is run with "foreman start lighthouse-watch beacon-7"
    And isaac is run with "foreman start lighthouse-watch beacon-9"
    And isaac is run with "foreman signal lighthouse-watch beacon-7 dusk"
    And isaac is run with "foreman signal lighthouse-watch beacon-9 dusk"
    Then session "lamp-beacon-7" has transcript matching:
      | type    | message.role | message.content             |
      | message | user         | Tend the lamp at beacon-7.  |
    And session "lamp-beacon-9" has transcript matching:
      | type    | message.role | message.content             |
      | message | user         | Tend the lamp at beacon-9.  |

  @wip
  Scenario: a machine test sees the rendered target
    Given a file "machine-tests/lighthouse.feature" exists with content:
      """
      Feature: lighthouse-watch

        Scenario: dusk tends the lamp in the instance's own session
          Given the "lighthouse-watch" machine
          And instance "beacon-7"
          And the state is "dark"
          When "dusk" is signaled
          Then the "tend-lamp" target is:
            | crew    | bartholomew   |
            | session | lamp-beacon-7 |
            | create  | :if-missing   |
      """
    When isaac is run with "foreman test machine-tests/lighthouse.feature"
    Then the stdout contains "PASS dusk tends the lamp in the instance's own session"
    And the exit code is 0
