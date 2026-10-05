Feature: Foreman — every turn knows its machine (isaac-ut5n)
  Every turn Foreman submits starts its preamble with one line naming the
  state machine, the instance, and the state the instance just entered:
  "This turn is part of Foreman machine <machine>, instance <instance>
  (state <state>)." A crew granted foreman__data can then pull the
  instance's data itself. The :output line follows it: storage for
  {:data :<key>}, the valid events for :event.

  Background:
    Given an Isaac root at "target/test-state"
    And default Grover setup
    And the isaac EDN file "config/crew/bartholomew.edn" exists with:
      | path  | value  |
      | model | grover |
    And the following sessions exist:
      | name      | crew        |
      | lamp-room | bartholomew |

  @wip
  Scenario: a turn with no output mode names its machine, instance and state
    Given the isaac file "config/machines/lighthouse-watch.edn" exists with:
      """
      {:initial :dark
       :actions {:tend-lamp {:type :turn :frequencies {:session "lamp-room"} :prompt "Tend the lamp."}}
       :transitions [{:start :dark :event :dusk :end :tending :actions [:tend-lamp]}]}
      """
    And the following model responses are queued:
      | type | content      | model |
      | text | Lamp tended. | echo  |
    When isaac is run with "foreman start lighthouse-watch beacon-7"
    And isaac is run with "foreman signal lighthouse-watch beacon-7 dusk"
    Then LLM request 1 matches:
      | key      | value                                                                                         |
      | messages | #"(?s)This turn is part of Foreman machine lighthouse-watch, instance beacon-7 \(state tending\)\." |

  @wip
  Scenario: a turn whose output is stored names them and says where its reply goes
    Given the isaac file "config/machines/lighthouse-watch.edn" exists with:
      """
      {:initial :dark
       :actions {:read-log {:type :turn :frequencies {:session "lamp-room"} :output {:data :summary} :prompt "Summarize the log."}}
       :transitions [{:start :dark :event :dusk :end :reading :actions [:read-log]}]}
      """
    And the following model responses are queued:
      | type | content          | model |
      | text | The oil is low.  | echo  |
    When isaac is run with "foreman start lighthouse-watch beacon-7"
    And isaac is run with "foreman signal lighthouse-watch beacon-7 dusk"
    Then LLM request 1 matches:
      | key      | value                                                                                                                  |
      | messages | #"(?s)This turn is part of Foreman machine lighthouse-watch, instance beacon-7 \(state reading\)\..*stored as the instance's summary" |

  @wip
  Scenario: a turn whose reply is its event names them and lists the events
    Given the isaac file "config/machines/lighthouse-watch.edn" exists with:
      """
      {:initial :dark
       :actions {:tend-lamp {:type :turn :frequencies {:session "lamp-room"} :output :event :prompt "Tend the lamp."}}
       :transitions [{:start :dark    :event :dusk :end :tending :actions [:tend-lamp]}
                     {:start :tending :event :lit  :end :lit}]}
      """
    And the following model responses are queued:
      | type | content                   | model |
      | text | Wick trimmed.\nevent: lit | echo  |
    When isaac is run with "foreman start lighthouse-watch beacon-7"
    And isaac is run with "foreman signal lighthouse-watch beacon-7 dusk"
    Then LLM request 1 matches:
      | key      | value                                                                                                    |
      | messages | #"(?s)This turn is part of Foreman machine lighthouse-watch, instance beacon-7 \(state tending\)\..*event: lit" |
