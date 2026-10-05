Feature: Foreman — a turn's reply as its event (isaac-79t1)
  With :output :event, the last line of a turn's reply names its outcome:
  "event: <name>" or "event: <name> <reason>". Foreman signals <name> as
  the turn's one outcome (source: reply) and puts <reason> in the event's
  data as :reason. Foreman writes the instructions into the turn's
  preamble, listing the events valid from the state the instance just
  entered (minus :foreman/*), so the machine's prompt describes only the
  task and needs no tool. A reply with no event line, or naming an event
  that is not on the list, is a quiet turn: Foreman reports
  :foreman/turn-ended, and a generic row can alert someone.

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
       :actions {:tend-lamp {:type        :turn
                             :frequencies {:session "lamp-room"}
                             :output      :event
                             :prompt      "Tend the lamp at {{instance}}."}}
       :transitions [{:start :dark    :event :dusk               :end :tending  :actions [:tend-lamp]}
                     {:start :tending :event :lit                :end :lit}
                     {:start :tending :event :spilled            :end :stranded}
                     {:start :lit     :event :dawn               :end :dark}
                     {:start :tending :event :foreman/turn-ended :end :unlit}]}
      """

  Scenario: the reply's last line names the event
    Given the following model responses are queued:
      | type | content                       | model |
      | text | Wick trimmed.\nevent: lit     | echo  |
    When isaac is run with "foreman start lighthouse-watch beacon-7"
    And isaac is run with "foreman signal lighthouse-watch beacon-7 dusk"
    And isaac is run with "foreman status lighthouse-watch beacon-7"
    Then the stdout matches:
      | pattern                                        |
      | beacon-7\s+lit                                 |
      | lit: tending -> lit\s+\[[^\]]+\] via reply     |
    And the stdout does not contain "turn-ended"

  Scenario: the rest of the event line becomes the reason
    Given the following model responses are queued:
      | type | content                                 | model |
      | text | No luck.\nevent: spilled the oil is gone | echo  |
    When isaac is run with "foreman start lighthouse-watch beacon-7"
    And isaac is run with "foreman signal lighthouse-watch beacon-7 dusk"
    And isaac is run with "foreman data lighthouse-watch beacon-7"
    Then the stdout matches:
      | pattern                        |
      | :reason\s+"the oil is gone"    |
    When isaac is run with "foreman status lighthouse-watch beacon-7"
    Then the stdout matches:
      | pattern               |
      | beacon-7\s+stranded   |

  Scenario: a reply with no event line is a quiet turn
    Given the following model responses are queued:
      | type | content                       | model |
      | text | I tended the lamp, I think.   | echo  |
    When isaac is run with "foreman start lighthouse-watch beacon-7"
    And isaac is run with "foreman signal lighthouse-watch beacon-7 dusk"
    And isaac is run with "foreman status lighthouse-watch beacon-7"
    Then the stdout matches:
      | pattern                                                  |
      | beacon-7\s+unlit                                         |
      | foreman/turn-ended: tending -> unlit\s+\[[^\]]+\] via observer |

  Scenario: an event that is not valid from this state is a quiet turn
    Given the following model responses are queued:
      | type | content               | model |
      | text | Done.\nevent: dawn    | echo  |
    When isaac is run with "foreman start lighthouse-watch beacon-7"
    And isaac is run with "foreman signal lighthouse-watch beacon-7 dusk"
    And isaac is run with "foreman status lighthouse-watch beacon-7"
    Then the stdout matches:
      | pattern            |
      | beacon-7\s+unlit   |

  Scenario: the preamble lists the events valid from the state just entered
    Given the following model responses are queued:
      | type | content                   | model |
      | text | Wick trimmed.\nevent: lit | echo  |
    When isaac is run with "foreman start lighthouse-watch beacon-7"
    And isaac is run with "foreman signal lighthouse-watch beacon-7 dusk"
    Then LLM request 1 matches:
      | key      | value                                  |
      | messages | #"(?s)event: lit"                      |
      | messages | #"(?s)event: spilled"                  |
    And the last LLM request does not contain "event: dawn"
