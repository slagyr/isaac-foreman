Feature: Foreman — turn actions
  A :turn action submits one turn to Agent when its transition fires:
  {:type :turn :frequencies {...} :resource-pools [...] :prompt "..."}.
  Foreman passes :frequencies and :resource-pools through untouched — it
  never selects a session or a directory. The prompt fills {{machine}},
  {{instance}}, and {{data.<key>}} from the triggering event. Foreman's
  turn observer rides every submitted turn, so its outcome returns as an
  event. Each submission carries the key <machine>/<instance>/<event-id>/
  <action>; Agent refuses a second request with the same key, so a retry
  never makes a second turn. A refused submission stays pending with its
  error until `foreman retry`. (isaac-lr8h)

  Background:
    Given an Isaac root at "target/test-state"
    And default Grover setup
    And the crew "bartholomew" allows tools: "foreman/signal"
    And the isaac EDN file "config/crew/bartholomew.edn" exists with:
      | path  | value  |
      | model | grover |
    And the following sessions exist:
      | name      | crew        |
      | lamp-room | bartholomew |
    And the isaac file "config/machines/lighthouse-watch.edn" exists with:
      """
      {:initial :dark
       :actions {:tend-lamp {:type        :turn
                             :frequencies {:session "lamp-room"}
                             :prompt      "Light the lamp at {{instance}} ({{machine}}); tide {{data.tide}}."}}
       :transitions [{:start :dark    :event :dusk       :end :tending :action [:tend-lamp]}
                     {:start :tending :event :lit        :end :lit}
                     {:start :tending :event :turn-ended :end :unlit}]}
      """

  Scenario: a transition submits one turn; the crew's signal moves the machine
    Given the following model responses are queued:
      | model | tool_call      | arguments                                                               |
      | echo  | foreman__signal | {"machine": "lighthouse-watch", "instance": "beacon-7", "event": "lit"} |
      | model | type           | content                                                                 |
      | echo  | text           | Lamp lit.                                                               |
    When isaac is run with "foreman start lighthouse-watch beacon-7"
    And isaac is run with "foreman signal lighthouse-watch beacon-7 dusk --data {:tide \"high\"}"
    Then the stdout contains "beacon-7: dark -> tending (dusk)"
    And session "lamp-room" has transcript matching:
      | type    | message.role | message.content                                           |
      | message | user         | Light the lamp at beacon-7 (lighthouse-watch); tide high. |
    When isaac is run with "foreman status lighthouse-watch beacon-7"
    Then the stdout matches:
      | pattern                                                      |
      | beacon-7\s+lit                                               |
      | tend-lamp \(turn\) submitted \S+                             |
      | (?s)lit: tending -> lit\s+\[[^\]]+\] via tool[\s\S]*unhandled: turn-ended |

  Scenario: a turn action waits on a busy pool; the unsignaled turn takes the backstop row
    Given a scripted resource pool "dock" admits 1 turn at a time
    And resource pool "dock" is closed
    And the isaac file "config/machines/lighthouse-watch.edn" exists with:
      """
      {:initial :dark
       :actions {:tend-lamp {:type           :turn
                             :frequencies    {:session "lamp-room"}
                             :resource-pools ["dock"]
                             :prompt         "Light the lamp at {{instance}}."}}
       :transitions [{:start :dark    :event :dusk       :end :tending :action [:tend-lamp]}
                     {:start :tending :event :lit        :end :lit}
                     {:start :tending :event :turn-ended :end :unlit}]}
      """
    And the following model responses are queued:
      | type | content               | model |
      | text | The keeper dozed off. | echo  |
    When isaac is run with "foreman start lighthouse-watch beacon-7"
    And isaac is run with "foreman signal lighthouse-watch beacon-7 dusk"
    Then the stdout contains "beacon-7: dark -> tending (dusk)"
    When isaac is run with "turns list"
    Then the stdout matches:
      | lamp-room |
      | dock      |
      | held      |
    When resource pool "dock" is opened
    Then session "lamp-room" has transcript matching:
      | type    | message.role | message.content       |
      | message | assistant    | The keeper dozed off. |
    When isaac is run with "foreman status lighthouse-watch beacon-7"
    Then the stdout matches:
      | pattern                                                  |
      | beacon-7\s+unlit                                         |
      | turn-ended: tending -> unlit\s+\[[^\]]+\] via observer   |

  Scenario: a refused submission stays pending with its error until retried
    Given the isaac file "config/machines/lighthouse-watch.edn" exists with:
      """
      {:initial :dark
       :actions {:tend-lamp {:type           :turn
                             :frequencies    {:session "lamp-room"}
                             :resource-pools ["drydock"]
                             :prompt         "Light the lamp at {{instance}}."}}
       :transitions [{:start :dark    :event :dusk       :end :tending :action [:tend-lamp]}
                     {:start :tending :event :turn-ended :end :unlit}]}
      """
    When isaac is run with "foreman start lighthouse-watch beacon-7"
    And isaac is run with "foreman signal lighthouse-watch beacon-7 dusk"
    Then the stdout contains "beacon-7: dark -> tending (dusk)"
    When isaac is run with "foreman status lighthouse-watch beacon-7"
    Then the stdout matches:
      | pattern                                                        |
      | beacon-7\s+tending                                             |
      | pending: tend-lamp \(turn\) failed: unknown resource pool drydock |
    Given a scripted resource pool "drydock" admits 1 turn at a time
    And the following model responses are queued:
      | type | content          | model |
      | text | Lamp trimmed.    | echo  |
    When isaac is run with "foreman retry lighthouse-watch beacon-7"
    Then the stdout contains "tend-lamp (turn) submitted"
    And the exit code is 0
    When isaac is run with "foreman status lighthouse-watch beacon-7"
    Then the stdout matches:
      | pattern                          |
      | beacon-7\s+unlit                 |
      | tend-lamp \(turn\) submitted \S+ |

  Scenario: a retry after Foreman lost the request id makes no second turn
    The instance file below is what Foreman leaves if it dies after Agent
    accepted the request but before the request id was recorded.
    Given a scripted resource pool "dock" admits 1 turn at a time
    And resource pool "dock" is closed
    And the isaac file "config/machines/lighthouse-watch.edn" exists with:
      """
      {:initial :dark
       :actions {:tend-lamp {:type           :turn
                             :frequencies    {:session "lamp-room"}
                             :resource-pools ["dock"]
                             :prompt         "Light the lamp at {{instance}}."}}
       :transitions [{:start :dark    :event :dusk       :end :tending :action [:tend-lamp]}
                     {:start :tending :event :turn-ended :end :unlit}]}
      """
    And the following model responses are queued:
      | type | content        | model |
      | text | First turn     | echo  |
      | text | Duplicate turn | echo  |
    When isaac is run with "foreman start lighthouse-watch beacon-7"
    And isaac is run with "foreman signal lighthouse-watch beacon-7 dusk --id tide-1"
    Given the isaac file "foreman/lighthouse-watch/beacon-7.edn" exists with:
      """
      {:state :tending :context {} :since "2026-03-01T18:00:00Z"
       :pending-actions [{:name :tend-lamp :type :turn :key "lighthouse-watch/beacon-7/tide-1/tend-lamp"}]}
      """
    When isaac is run with "foreman retry lighthouse-watch beacon-7"
    Then the stdout contains "tend-lamp (turn) submitted"
    When resource pool "dock" is opened
    Then session "lamp-room" has transcript matching:
      | type    | message.role | message.content |
      | message | assistant    | First turn      |
    And session "lamp-room" has transcript not matching:
      | type    | message.role | message.content |
      | message | assistant    | Duplicate turn  |
