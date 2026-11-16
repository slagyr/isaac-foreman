Feature: Foreman — stall alerts go to the dispatcher (isaac-wsm6)
  The agent that dispatches an instance names where its alerts go by
  starting it with data {:alert {:session "<its session>"}}. A machine's
  alert action targets {{data.alert.session}}, so the stall alert is a turn
  in the dispatcher's own session. A :turn action whose session renders
  empty starts no turn; the instance's history records the skip. A
  dispatcher outside Isaac watches the stream (stream.feature) instead.

  Background:
    Given an Isaac root at "target/test-state"
    And default Grover setup
    And the isaac EDN file "config/crew/bartholomew.edn" exists with:
      | path  | value  |
      | model | grover |
    And the following sessions exist:
      | name      | crew        |
      | lamp-room | bartholomew |
      | plan-desk | bartholomew |
    And the isaac file "config/machines/lamp-tending.edn" exists with:
      """
      {:initial :dark
       :actions {:tend-lamp {:type        :turn
                             :frequencies {:session "lamp-room"}
                             :output      :event
                             :prompt      "Tend the lamp at {{instance}}."}
                 :alert     {:type        :turn
                             :frequencies {:session "{{data.alert.session}}"}
                             :prompt      "{{instance}} needs attention: {{data.reason}}"}}
       :transitions [{:start :dark    :event :dusk    :end :tending  :actions [:tend-lamp]}
                     {:start :tending :event :lit     :end :lit}
                     {:start :tending :event :spilled :end :stranded :actions [:alert]}]}
      """

  Scenario: a stall alerts the session that dispatched the instance
    Given the following model responses are queued:
      | type | content                                  | model |
      | text | No luck.\nevent: spilled the oil is gone | echo  |
      | text | Looking into it.                         | echo  |
    When isaac is run with "foreman start lamp-tending beacon-7 --data {:alert {:session \"plan-desk\"}}"
    And isaac is run with "foreman signal lamp-tending beacon-7 dusk"
    Then session "plan-desk" has transcript matching:
      | type    | message.role | message.content                                      |
      | message | user         | #"(?s).*beacon-7 needs attention: the oil is gone.*" |

  Scenario: an instance with no alert session starts no alert turn
    Given the following model responses are queued:
      | type | content                                  | model |
      | text | No luck.\nevent: spilled the oil is gone | echo  |
    When isaac is run with "foreman start lamp-tending beacon-7"
    And isaac is run with "foreman signal lamp-tending beacon-7 dusk"
    And isaac is run with "foreman status lamp-tending beacon-7"
    Then the stdout matches:
      | pattern                          |
      | beacon-7\s+stranded              |
      | alert skipped: no target session |
