Feature: Foreman — event stream (isaac-wsm6)
  A dispatcher watches its machines instead of polling them.
  GET /foreman/stream?machine=<id>[&instance=<id>] answers text/event-stream
  and pushes one JSON event per transition as it happens, whichever door the
  event came through (tool, HTTP, CLI, reply, observer):
  {type: "transition", machine, instance, id, event, from, to, reason, at}.
  On connect it first sends each live instance's current state:
  {type: "state", machine, instance, state}. Reading the stream needs
  :foreman/read.

  Background:
    Given an Isaac root at "target/test-state"
    And default Grover setup
    And principal "keeper" is configured with secret "secret123" and scopes "*"
    And the isaac file "config/machines/lighthouse-watch.edn" exists with:
      """
      {:initial :dark
       :transitions [{:start :dark :event :dusk :end :lit}
                     {:start :lit  :event :dawn :end :dark}]}
      """

  Scenario: a stream client receives a transition as it happens, from any door
    When isaac is run with "foreman start lighthouse-watch beacon-7"
    And a client opens a stream to "/foreman/stream?machine=lighthouse-watch":
      | key                  | value            |
      | header.Authorization | Bearer secret123 |
    And isaac is run with "foreman signal lighthouse-watch beacon-7 dusk --id tide-42"
    Then the stream delivers an event matching:
      | key      | value            |
      | type     | transition       |
      | machine  | lighthouse-watch |
      | instance | beacon-7         |
      | id       | tide-42          |
      | event    | dusk             |
      | from     | dark             |
      | to       | lit              |

  Scenario: a stall arrives on the stream with the turn's reason
    Given the isaac EDN file "config/crew/bartholomew.edn" exists with:
      | path  | value  |
      | model | grover |
    And the following sessions exist:
      | name      | crew        |
      | lamp-room | bartholomew |
    And the isaac file "config/machines/lamp-tending.edn" exists with:
      """
      {:initial :dark
       :actions {:tend-lamp {:type        :turn
                             :frequencies {:session "lamp-room"}
                             :output      :event
                             :prompt      "Tend the lamp at {{instance}}."}}
       :transitions [{:start :dark    :event :dusk    :end :tending  :actions [:tend-lamp]}
                     {:start :tending :event :lit     :end :lit}
                     {:start :tending :event :spilled :end :stranded}]}
      """
    And the following model responses are queued:
      | type | content                                  | model |
      | text | No luck.\nevent: spilled the oil is gone | echo  |
    When isaac is run with "foreman start lamp-tending beacon-7"
    And a client opens a stream to "/foreman/stream?machine=lamp-tending":
      | key                  | value            |
      | header.Authorization | Bearer secret123 |
    And isaac is run with "foreman signal lamp-tending beacon-7 dusk"
    Then the stream delivers an event matching:
      | key      | value           |
      | type     | transition      |
      | instance | beacon-7        |
      | event    | spilled         |
      | from     | tending         |
      | to       | stranded        |
      | reason   | the oil is gone |

  Scenario: a watcher that connects late first gets each live instance's state
    When isaac is run with "foreman start lighthouse-watch beacon-7"
    And isaac is run with "foreman signal lighthouse-watch beacon-7 dusk"
    And isaac is run with "foreman start lighthouse-watch beacon-9"
    And a client opens a stream to "/foreman/stream?machine=lighthouse-watch":
      | key                  | value            |
      | header.Authorization | Bearer secret123 |
    Then the stream delivers an event matching:
      | key      | value    |
      | type     | state    |
      | instance | beacon-7 |
      | state    | lit      |
    And the stream delivers an event matching:
      | key      | value    |
      | type     | state    |
      | instance | beacon-9 |
      | state    | dark     |

  Scenario: an instance filter streams only that instance
    When isaac is run with "foreman start lighthouse-watch beacon-7"
    And isaac is run with "foreman start lighthouse-watch beacon-9"
    And a client opens a stream to "/foreman/stream?machine=lighthouse-watch&instance=beacon-9":
      | key                  | value            |
      | header.Authorization | Bearer secret123 |
    And isaac is run with "foreman signal lighthouse-watch beacon-7 dusk"
    And isaac is run with "foreman signal lighthouse-watch beacon-9 dusk --id tide-9"
    Then the stream delivers an event matching:
      | key      | value      |
      | type     | transition |
      | instance | beacon-9   |
      | id       | tide-9     |
    And the stream has delivered no event matching:
      | key      | value    |
      | instance | beacon-7 |

  Scenario: a token without foreman/read cannot open the stream
    Given principal "lamplighter" is configured with secret "lamp123" and scopes "hail/send"
    When a client opens a stream to "/foreman/stream?machine=lighthouse-watch":
      | key                  | value          |
      | header.Authorization | Bearer lamp123 |
    Then the response status is 403

  @wip
  Scenario: two watchers each receive a transition exactly once (isaac-18wa)
    When isaac is run with "foreman start lighthouse-watch beacon-7"
    And a client opens a stream to "/foreman/stream?machine=lighthouse-watch":
      | key                  | value            |
      | header.Authorization | Bearer secret123 |
    And a second client opens a stream to "/foreman/stream?machine=lighthouse-watch":
      | key                  | value            |
      | header.Authorization | Bearer secret123 |
    And isaac is run with "foreman signal lighthouse-watch beacon-7 dusk --id tide-42"
    Then the stream delivers an event matching:
      | key  | value      |
      | type | transition |
      | id   | tide-42    |
    And the second stream delivers an event matching:
      | key  | value      |
      | type | transition |
      | id   | tide-42    |
    And the stream has delivered exactly 1 event matching:
      | key | value   |
      | id  | tide-42 |
    And the second stream has delivered exactly 1 event matching:
      | key | value   |
      | id  | tide-42 |

  @wip
  Scenario: a watcher that disconnects leaves later signals and new watchers unharmed (isaac-18wa)
    When isaac is run with "foreman start lighthouse-watch beacon-7"
    And a client opens a stream to "/foreman/stream?machine=lighthouse-watch":
      | key                  | value            |
      | header.Authorization | Bearer secret123 |
    And the client closes the stream
    And isaac is run with "foreman signal lighthouse-watch beacon-7 dusk --id tide-43"
    Then the exit code is 0
    When a second client opens a stream to "/foreman/stream?machine=lighthouse-watch":
      | key                  | value            |
      | header.Authorization | Bearer secret123 |
    Then the second stream delivers an event matching:
      | key      | value    |
      | type     | state    |
      | instance | beacon-7 |
      | state    | lit      |
