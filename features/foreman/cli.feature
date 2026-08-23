Feature: Foreman — CLI
  `isaac foreman` drives and inspects machine instances: start births an
  instance at :initial, signal fires events, status shows one instance
  (state, since, pending actions, history), list surveys a machine.
  Every transition appends to the instance's durable history before it
  is acknowledged.

  Background:
    Given an Isaac root at "isaac-state"
    And config file "isaac.edn" containing:
      """
      {:machines
       {"lighthouse-watch"
        {:initial :dark
         :actions {:light-lamp {:type :log :message "lamp lit"}
                   :douse-lamp {:type :log :message "lamp doused"}}
         :transitions [{:start :dark :event :dusk :end :lit  :action [:light-lamp]}
                       {:start :lit  :event :dawn :end :dark :action [:douse-lamp]}]}}}
      """

  @wip
    Scenario: start births an instance; signal moves it; status tells the story
    Given the current time is "2026-03-01T18:00:00"
    When isaac is run with "foreman start lighthouse-watch beacon-7"
    Then the stdout contains "beacon-7: dark"
    And the exit code is 0
    When isaac is run with "foreman signal lighthouse-watch beacon-7 dusk"
    Then the stdout contains "beacon-7: dark -> lit (dusk)"
    And the exit code is 0
    When isaac is run with "foreman status lighthouse-watch beacon-7"
    Then the stdout matches:
      | pattern                                  |
      | beacon-7\s+lit\s+since 2026-03-01T18:00 |
      | dusk: dark -> lit                        |
    And the exit code is 0
    When isaac is run with "foreman start lighthouse-watch beacon-7"
    Then the stderr contains "already exists"
    And the exit code is 1
    When isaac is run with "foreman signal lighthouse-watch ghost-9 dusk"
    Then the stderr contains "ghost-9"
    And the stderr contains "unknown instance"
    And the exit code is 1

  @wip
    Scenario: actions are recorded as pending; only log actions execute
    Given config file "isaac.edn" containing:
      """
      {:machines
       {"lighthouse-watch"
        {:initial :dark
         :actions {:light-lamp  {:type :log :message "lamp lit"}
                   :call-keeper {:type :hail :band "keepers"}}
         :transitions [{:start :dark :event :dusk :end :lit
                        :action [:light-lamp :call-keeper]}]}}}
      """
    When isaac is run with "foreman start lighthouse-watch beacon-7"
    When isaac is run with "foreman signal lighthouse-watch beacon-7 dusk"
    Then the stdout contains "lamp lit"
    And the exit code is 0
    When isaac is run with "foreman status lighthouse-watch beacon-7"
    Then the stdout matches:
      | pattern                       |
      | beacon-7\s+lit                |
      | pending: call-keeper \(hail\) |
    And the stdout does not contain "pending: light-lamp"

  @wip
    Scenario: unhandled events are recorded, never fatal
    When isaac is run with "foreman start lighthouse-watch beacon-7"
    When isaac is run with "foreman signal lighthouse-watch beacon-7 earthquake"
    Then the stderr contains "unhandled: earthquake (state dark)"
    And the exit code is 0
    When isaac is run with "foreman status lighthouse-watch beacon-7"
    Then the stdout matches:
      | pattern               |
      | beacon-7\s+dark       |
      | unhandled: earthquake |
    When isaac is run with "foreman signal lighthouse-watch beacon-7 dusk"
    Then the stdout contains "beacon-7: dark -> lit (dusk)"
    And the exit code is 0

  @wip
    Scenario: list surveys a machine's instances, filterable by state
    Given the current time is "2026-03-01T18:00:00"
    When isaac is run with "foreman start lighthouse-watch beacon-7"
    When isaac is run with "foreman start lighthouse-watch beacon-9"
    When isaac is run with "foreman start lighthouse-watch beacon-11"
    When isaac is run with "foreman signal lighthouse-watch beacon-9 dusk"
    When isaac is run with "foreman signal lighthouse-watch beacon-11 dusk"
    When isaac is run with "foreman signal lighthouse-watch beacon-11 dawn"
    When isaac is run with "foreman list lighthouse-watch"
    Then the stdout matches:
      | pattern                       |
      | beacon-7\s+dark\s+since 2026  |
      | beacon-9\s+lit\s+since 2026   |
      | beacon-11\s+dark\s+since 2026 |
    And the exit code is 0
    When isaac is run with "foreman list lighthouse-watch --state lit"
    Then the stdout contains "beacon-9"
    And the stdout does not contain "beacon-7"
    And the stdout does not contain "beacon-11"
    And the exit code is 0
