Feature: Foreman — machine tests
  `isaac foreman test <file.feature>…` runs Gherkin tests against the
  machines in the current Isaac root's config. Foreman provides the steps;
  a test author writes only .feature files. Tests walk the pure engine:
  actions are recorded, never run — no turn is submitted, no instance is
  created. One line per scenario: PASS, or FAIL with the first mismatch.
  Exit 0 only when every scenario passes. Relative paths resolve against
  the Isaac root. (isaac-1qgv)

  Steps — one rule: "is" = exact, "contains" = substring or membership,
  "matches" = regex:
    Given the "<machine>" machine | Given instance "<id>" | Given the state is "<state>"
    When "<event>" is signaled
    Then the state is "<state>" | Then the signal is unhandled
    Then the actions are "<a>, <b>" | Then the actions contain "<a>" | Then there are no actions
    Then the "<action>" prompt is / contains / matches "<text>"
    Then the "<action>" target is: (key | value table)

  Background:
    Given an Isaac root at "target/test-state"
    And default Grover setup
    And the isaac file "config/machines/lighthouse-watch.edn" exists with:
      """
      {:initial :dark
       :actions {:light-lamp {:type :turn :frequencies {:crew "bartholomew"} :prompt "Light the lamp at {{instance}}."}
                 :douse-lamp {:type :turn :frequencies {:crew "bartholomew"} :prompt "Douse the lamp at {{instance}}."}
                 :log-watch  {:type :log :message "watch changed"}}
       :transitions [{:start :dark :event :dusk :end :lit  :actions [:light-lamp :log-watch]}
                     {:start :lit  :event :dawn :end :dark :actions [:douse-lamp :log-watch]}]}
      """

  Scenario: a passing machine test prints PASS and leaves nothing behind
    Given a file "machine-tests/lighthouse.feature" exists with content:
      """
      Feature: lighthouse-watch

        Background:
          Given the "lighthouse-watch" machine
          And instance "beacon-7"

        Scenario: dusk lights the lamp
          When "dusk" is signaled
          Then the state is "lit"
          And the actions are "light-lamp, log-watch"
          And the "light-lamp" target is:
            | crew | bartholomew |

        Scenario: a full night's watch returns to dark
          When "dusk" is signaled
          And "dawn" is signaled
          Then the state is "dark"
          And the actions contain "douse-lamp"
      """
    When isaac is run with "foreman test machine-tests/lighthouse.feature"
    Then the stdout matches:
      | pattern                                  |
      | PASS dusk lights the lamp                |
      | PASS a full night's watch returns to dark |
    And the stdout does not contain "watch changed"
    And the exit code is 0
    When isaac is run with "foreman list lighthouse-watch"
    Then the stdout does not contain "beacon-7"
    When isaac is run with "turns list --all"
    Then the stdout is empty

  Scenario: a wrong expected state fails with the step, the expectation, and the actual state
    Given a file "machine-tests/lighthouse.feature" exists with content:
      """
      Feature: lighthouse-watch

        Scenario: dusk leaves it dark
          Given the "lighthouse-watch" machine
          When "dusk" is signaled
          Then the state is "dark"
      """
    When isaac is run with "foreman test machine-tests/lighthouse.feature"
    Then the stdout matches:
      | pattern                                                                   |
      | FAIL dusk leaves it dark .*Then the state is "dark".*expected dark, got lit |
    And the exit code is 1

  Scenario: prompts are checked exactly, by substring, or by regex; a miss shows the actual prompt
    Given a file "machine-tests/lighthouse.feature" exists with content:
      """
      Feature: lighthouse-watch

        Background:
          Given the "lighthouse-watch" machine
          And instance "beacon-7"

        Scenario: the prompt names the beacon
          When "dusk" is signaled
          Then the "light-lamp" prompt is "Light the lamp at beacon-7."
          And the "light-lamp" prompt contains "beacon-7"
          And the "light-lamp" prompt matches "Light the lamp at beacon-\d+\."

        Scenario: the prompt names the wrong beacon
          When "dusk" is signaled
          Then the "light-lamp" prompt contains "beacon-9"
      """
    When isaac is run with "foreman test machine-tests/lighthouse.feature"
    Then the stdout matches:
      | pattern                                                                 |
      | PASS the prompt names the beacon                                        |
      | FAIL the prompt names the wrong beacon .*Light the lamp at beacon-7\.   |
    And the exit code is 1

  Scenario: an expected unhandled signal passes; an unexpected one fails naming the event and state
    Given a file "machine-tests/lighthouse.feature" exists with content:
      """
      Feature: lighthouse-watch

        Background:
          Given the "lighthouse-watch" machine

        Scenario: dawn before dusk is unhandled
          When "dawn" is signaled
          Then the signal is unhandled
          And the state is "dark"
          And there are no actions

        Scenario: dawn before dusk, unexpectedly
          When "dawn" is signaled
          Then the state is "lit"
      """
    When isaac is run with "foreman test machine-tests/lighthouse.feature"
    Then the stdout matches:
      | pattern                                                                      |
      | PASS dawn before dusk is unhandled                                           |
      | FAIL dawn before dusk, unexpectedly .*no transition for dawn from dark       |
    And the exit code is 1
