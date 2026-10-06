Feature: Foreman — turn outcomes (isaac-8j0t)
  Each turn Foreman submits has one outcome. A turn that signals (tool,
  or later its reply) has that signal as its outcome; Foreman reports
  :foreman/turn-ended only for a turn that sent none. :foreman/turn-failed
  and :foreman/turn-died are always reported. The :foreman namespace is
  reserved: the CLI, HTTP and the foreman__signal tool refuse to send it.
  A :turn or :exec action may say what its output becomes with one key,
  :output — {:data :<key>} stores it in the instance data before the
  turn's outcome is applied. A turn with :output gets Foreman-written
  instructions in its preamble, so the machine's prompts describe only
  the task.

  Background:
    Given an Isaac root at "target/test-state"
    And default Grover setup
    And the isaac EDN file "config/crew/bartholomew.edn" exists with:
      | path  | value  |
      | model | grover |
    And the following sessions exist:
      | name      | crew        |
      | lamp-room | bartholomew |
    And the isaac file "config/machines/lighthouse-watch.edn" exists with:
      """
      {:initial :dark
       :actions {:read-log  {:type        :turn
                             :frequencies {:session "lamp-room"}
                             :output      {:data :summary}
                             :prompt      "Summarize the keeper's log for {{instance}}."}
                 :tend-lamp {:type        :turn
                             :frequencies {:session "lamp-room"}
                             :prompt      "Tend {{instance}}: {{data.summary}}"}}
       :transitions [{:start :dark    :event :dusk               :end :reading :actions [:read-log]}
                     {:start :reading :event :foreman/turn-ended :end :tending :actions [:tend-lamp]}]}
      """

  Scenario: a turn's output is stored in the data, and the next action uses it
    Given the following model responses are queued:
      | type | content          | model |
      | text | The oil is low.  | echo  |
      | text | Lamp tended.     | echo  |
    When isaac is run with "foreman start lighthouse-watch beacon-7"
    And isaac is run with "foreman signal lighthouse-watch beacon-7 dusk"
    Then session "lamp-room" has transcript matching:
      | type    | message.role | message.content                 |
      | message | user         | Tend beacon-7: The oil is low.  |
    When isaac is run with "foreman data lighthouse-watch beacon-7"
    Then the stdout matches:
      | pattern                       |
      | :summary\s+"The oil is low."  |

  Scenario: a turn whose output is stored is told so in its preamble
    Given the following model responses are queued:
      | type | content          | model |
      | text | The oil is low.  | echo  |
      | text | Lamp tended.     | echo  |
    When isaac is run with "foreman start lighthouse-watch beacon-7"
    And isaac is run with "foreman signal lighthouse-watch beacon-7 dusk"
    Then LLM request 1 matches:
      | key      | value                                        |
      | messages | #"(?s)stored as the instance's summary"      |

  Scenario: the foreman namespace is reserved for Foreman's own events
    When isaac is run with "foreman start lighthouse-watch beacon-7"
    And isaac is run with "foreman signal lighthouse-watch beacon-7 foreman/turn-ended"
    Then the stderr contains "reserved"
    And the exit code is 1
    When isaac is run with "foreman status lighthouse-watch beacon-7"
    Then the stdout matches:
      | pattern          |
      | beacon-7\s+dark  |

  @wip
  Scenario: a dropped turn reaches the machine as turn-died (isaac-tais)
    Given a scripted resource pool "dock" admits 1 turn at a time
    And resource pool "dock" is closed
    And the isaac file "config/machines/lighthouse-watch.edn" exists with:
      """
      {:initial :dark
       :actions {:tend-lamp {:type           :turn
                             :frequencies    {:session "lamp-room"}
                             :resource-pools ["dock"]
                             :prompt         "Light the lamp at {{instance}}."}}
       :transitions [{:start :dark    :event :dusk              :end :tending :actions [:tend-lamp]}
                     {:start :tending :event :foreman/turn-died :end :stalled}]}
      """
    When isaac is run with "foreman start lighthouse-watch beacon-7"
    And isaac is run with "foreman signal lighthouse-watch beacon-7 dusk"
    And isaac is run with "foreman status lighthouse-watch beacon-7"
    Then the stdout contains "tend-lamp (turn) submitted"
    When isaac is run with "turns drop #turn-id"
    Then the exit code is 0
    When isaac is run with "foreman status lighthouse-watch beacon-7"
    Then the stdout matches:
      | pattern            |
      | beacon-7\s+stalled |
