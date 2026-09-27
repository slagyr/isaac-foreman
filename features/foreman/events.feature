@wip
Feature: Foreman — event intake
  Events reach a machine instance through several doors: the crew
  foreman-signal tool, POST /foreman/events, the CLI, and turn observers.
  Every door writes the same envelope to the instance's events.ednl
  before acknowledging it. Consuming the event appends its transition
  (or unhandled record) with the event id. A repeated id changes nothing.
  A turn joins an instance through the observer ref
  foreman:<machine>/<instance> and reports :turn-started, :turn-ended,
  :turn-failed, and :turn-died. A :turn-ended row keyed on a state the
  crew should have signaled out of is the machine's backstop.

  Background:
    Given an Isaac root at "target/test-state"
    And default Grover setup
    And the isaac file "config/machines/lighthouse-watch.edn" exists with:
      """
      {:initial :dark
       :transitions [{:start :dark :event :dusk :end :lit}
                     {:start :lit  :event :dawn :end :dark}]}
      """

  Scenario: the foreman-signal tool moves an instance and records the signaling crew
    Given the crew "bartholomew" allows tools: "foreman-signal"
    And the isaac EDN file "config/crew/bartholomew.edn" exists with:
      | path  | value  |
      | model | grover |
    And the following sessions exist:
      | name      | crew        |
      | lamp-room | bartholomew |
    When isaac is run with "foreman start lighthouse-watch beacon-7"
    Given the following model responses are queued:
      | model | tool_call      | arguments                                                                |
      | echo  | foreman-signal | {"machine": "lighthouse-watch", "instance": "beacon-7", "event": "dusk"} |
      | model | type           | content                                                                  |
      | echo  | text           | The lamp is lit.                                                         |
    When the user sends "the sun is setting" on session "lamp-room"
    Then session "lamp-room" has transcript matching:
      | type    | message.role | message.content                           |
      | message | toolResult   | #"(?s).*beacon-7: dark -> lit \(dusk\).*" |
    When isaac is run with "foreman status lighthouse-watch beacon-7"
    Then the stdout matches:
      | pattern                                                                         |
      | beacon-7\s+lit                                                                  |
      | dusk: dark -> lit\s+\[[^\]]+\] via tool \(crew bartholomew, session lamp-room\) |

  Scenario: POST /foreman/events acknowledges with an event id; an unknown instance is refused
    When isaac is run with "foreman start lighthouse-watch beacon-7"
    And a POST request is made to "/foreman/events":
      | key                  | value                                                                    |
      | header.Content-Type  | application/json                                                         |
      | header.Authorization | Bearer secret123                                                         |
      | body                 | {"machine": "lighthouse-watch", "instance": "beacon-7", "event": "dusk"} |
    Then the response status is 202
    And the response body has a "id" key
    When isaac is run with "foreman status lighthouse-watch beacon-7"
    Then the stdout matches:
      | pattern                                     |
      | beacon-7\s+lit                              |
      | dusk: dark -> lit\s+\[[^\]]+\] via http     |
    When a POST request is made to "/foreman/events":
      | key                  | value                                                                   |
      | header.Content-Type  | application/json                                                        |
      | header.Authorization | Bearer secret123                                                        |
      | body                 | {"machine": "lighthouse-watch", "instance": "ghost-9", "event": "dusk"} |
    Then the response status is 404
    And the response body has a "error" key
    When isaac is run with "foreman status lighthouse-watch ghost-9"
    Then the stderr contains "unknown instance"
    And the exit code is 1

  Scenario: a repeated event id changes state once, whichever door it comes through
    When isaac is run with "foreman start lighthouse-watch beacon-7"
    And isaac is run with "foreman signal lighthouse-watch beacon-7 dusk --id tide-42"
    Then the stdout contains "beacon-7: dark -> lit (dusk)"
    When a POST request is made to "/foreman/events":
      | key                  | value                                                                                    |
      | header.Content-Type  | application/json                                                                         |
      | header.Authorization | Bearer secret123                                                                         |
      | body                 | {"id": "tide-42", "machine": "lighthouse-watch", "instance": "beacon-7", "event": "dusk"} |
    Then the response status is 202
    And the response body has a "duplicate" key
    When isaac is run with "foreman status lighthouse-watch beacon-7"
    Then the stdout matches:
      | pattern                                 |
      | beacon-7\s+lit                          |
      | dusk: dark -> lit\s+\[tide-42\] via cli |
      | duplicate: dusk\s+\[tide-42\] via http  |
    And the stdout does not contain "unhandled: dusk"

  Scenario: an acknowledged event left unconsumed is applied before the next one
    The received record with no result stands in for a process that
    died between the acknowledgement and the transition.
    When isaac is run with "foreman start lighthouse-watch beacon-7"
    Given the isaac file "foreman/lighthouse-watch/beacon-7.events.ednl" exists with:
      """
      {:type :received :id "tide-7" :machine "lighthouse-watch" :instance "beacon-7" :event :dusk :source :http :at "2026-03-01T18:00:00Z"}
      """
    When isaac is run with "foreman signal lighthouse-watch beacon-7 dawn --id tide-8"
    Then the stdout matches:
      | pattern                                                         |
      | (?s)beacon-7: dark -> lit \(dusk\).*beacon-7: lit -> dark \(dawn\) |
    When isaac is run with "foreman status lighthouse-watch beacon-7"
    Then the stdout matches:
      | pattern                                                                    |
      | beacon-7\s+dark                                                            |
      | (?s)dusk: dark -> lit\s+\[tide-7\] via http.*dawn: lit -> dark\s+\[tide-8\] via cli |

  Scenario: a turn that signals moves the instance; its observations are recorded unhandled
    Given the crew "bartholomew" allows tools: "foreman-signal"
    And the isaac EDN file "config/crew/bartholomew.edn" exists with:
      | path  | value  |
      | model | grover |
    And the following sessions exist:
      | name      | crew        |
      | lamp-room | bartholomew |
    When isaac is run with "foreman start lighthouse-watch beacon-7"
    Given the following model responses are queued:
      | model | tool_call      | arguments                                                                |
      | echo  | foreman-signal | {"machine": "lighthouse-watch", "instance": "beacon-7", "event": "dusk"} |
      | model | type           | content                                                                  |
      | echo  | text           | The lamp is lit.                                                         |
    When isaac is run with "prompt -m 'The sun is setting' --session lamp-room --observer foreman:lighthouse-watch/beacon-7"
    Then the exit code is 0
    When isaac is run with "foreman status lighthouse-watch beacon-7"
    Then the stdout matches:
      | pattern                                                                                          |
      | beacon-7\s+lit                                                                                   |
      | (?s)unhandled: turn-started.*dusk: dark -> lit\s+\[[^\]]+\] via tool.*unhandled: turn-ended |

  Scenario: a turn that ends without signaling follows the backstop row; a failed turn follows its own
    Given the isaac file "config/machines/lighthouse-watch.edn" exists with:
      """
      {:initial :dark
       :transitions [{:start :dark :event :dusk        :end :lit}
                     {:start :dark :event :turn-ended  :end :unlit}
                     {:start :dark :event :turn-failed :end :storm-bound}]}
      """
    And the following sessions exist:
      | name      |
      | lamp-room |
    When isaac is run with "foreman start lighthouse-watch beacon-7"
    And isaac is run with "foreman start lighthouse-watch beacon-9"
    Given the following model responses are queued:
      | type | content               | model |
      | text | The keeper dozed off. | echo  |
    When isaac is run with "prompt -m 'Light the lamp' --session lamp-room --observer foreman:lighthouse-watch/beacon-7"
    Then the exit code is 0
    Given the following model responses are queued:
      | type       | status | message          | model |
      | http-error | 400    | lamp oil spilled | echo  |
    When isaac is run with "prompt -m 'Light the lamp' --session lamp-room --observer foreman:lighthouse-watch/beacon-9"
    Then the exit code is 1
    When isaac is run with "foreman list lighthouse-watch"
    Then the stdout matches:
      | pattern                  |
      | beacon-7\s+unlit         |
      | beacon-9\s+storm-bound   |
    When isaac is run with "foreman status lighthouse-watch beacon-7"
    Then the stdout matches:
      | pattern                                          |
      | turn-ended: dark -> unlit\s+\[[^\]]+\] via observer |
