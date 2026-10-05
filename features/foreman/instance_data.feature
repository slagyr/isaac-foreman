Feature: Foreman — instance data (isaac-7sfq)
  Every instance carries one data map. `foreman start --data EDN` seeds
  it. A handled event's data merges into it (shallow; a nil value removes
  the key) before the transition's actions run; a refused event changes
  nothing. `{{data.<key>}}` in a :turn prompt reads the merged instance
  data, dotted paths reach nested maps, and a missing key fills empty.
  Data can also be set without a transition: `foreman data --set`,
  POST /foreman/instances/<machine>/<id>/data, and the foreman__data
  tool. `foreman data` with no flag and `foreman status` print it. The
  event log keeps each event's own data as history.

  Background:
    Given an Isaac root at "target/test-state"
    And default Grover setup
    And principal "keeper" is configured with secret "secret123" and scopes "*"
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
                             :prompt      "Light the lamp at {{instance}} for {{data.keeper.name}}; tide {{data.tide}}."}}
       :transitions [{:start :dark    :event :dusk       :end :tending :actions [:tend-lamp]}
                     {:start :tending :event :lit        :end :lit}
                     {:start :tending :event :foreman/turn-ended :end :unlit}]}
      """

  Scenario: start seeds the data and a later turn renders it, nested keys included
    Given the following model responses are queued:
      | type | content    | model |
      | text | Lamp lit.  | echo  |
    When isaac is run with "foreman start lighthouse-watch beacon-7 --data {:tide \"low\" :keeper {:name \"Atticus\"}}"
    Then the exit code is 0
    When isaac is run with "foreman signal lighthouse-watch beacon-7 dusk"
    Then session "lamp-room" has transcript matching:
      | type    | message.role | message.content                                 |
      | message | user         | Light the lamp at beacon-7 for Atticus; tide low. |

  Scenario: a handled event's data merges before its actions run; nil removes a key
    Given the following model responses are queued:
      | type | content    | model |
      | text | Lamp lit.  | echo  |
    When isaac is run with "foreman start lighthouse-watch beacon-7 --data {:tide \"low\" :keeper {:name \"Atticus\"}}"
    And isaac is run with "foreman signal lighthouse-watch beacon-7 dusk --data {:tide \"high\" :keeper nil}"
    Then session "lamp-room" has transcript matching:
      | type    | message.role | message.content                           |
      | message | user         | Light the lamp at beacon-7 for ; tide high. |
    When isaac is run with "foreman data lighthouse-watch beacon-7"
    Then the stdout matches:
      | pattern          |
      | :tide\s+"high"   |
    And the stdout does not contain "keeper"
    And the exit code is 0

  Scenario: a refused event leaves the data unchanged
    When isaac is run with "foreman start lighthouse-watch beacon-7 --data {:tide \"low\"}"
    And isaac is run with "foreman signal lighthouse-watch beacon-7 earthquake --data {:tide \"high\"}"
    Then the exit code is 1
    When isaac is run with "foreman data lighthouse-watch beacon-7"
    Then the stdout matches:
      | pattern        |
      | :tide\s+"low"  |
    And the stdout does not contain "high"

  Scenario: foreman data --set merges without a transition, and status shows the data
    When isaac is run with "foreman start lighthouse-watch beacon-7 --data {:tide \"low\"}"
    And isaac is run with "foreman data lighthouse-watch beacon-7 --set {:tide \"high\" :keeper {:name \"Mabel\"}}"
    Then the exit code is 0
    When isaac is run with "foreman status lighthouse-watch beacon-7"
    Then the stdout matches:
      | pattern            |
      | beacon-7\s+dark    |
      | :tide\s+"high"     |
      | :name\s+"Mabel"    |
    When isaac is run with "foreman data lighthouse-watch ghost-9 --set {:tide \"high\"}"
    Then the stderr contains "unknown instance"
    And the exit code is 1

  Scenario: POST /foreman/instances/<machine>/<id>/data merges data over HTTP
    When isaac is run with "foreman start lighthouse-watch beacon-7 --data {:tide \"low\"}"
    And a POST request is made to "/foreman/instances/lighthouse-watch/beacon-7/data":
      | key                  | value                                     |
      | header.Content-Type  | application/json                          |
      | header.Authorization | Bearer secret123                          |
      | body                 | {"tide": "ebb", "keeper": {"name": "Ada"}} |
    Then the response status is 200
    When isaac is run with "foreman data lighthouse-watch beacon-7"
    Then the stdout matches:
      | pattern         |
      | :tide\s+"ebb"   |
      | :name\s+"Ada"   |
    When a POST request is made to "/foreman/instances/lighthouse-watch/ghost-9/data":
      | key                  | value            |
      | header.Content-Type  | application/json |
      | header.Authorization | Bearer secret123 |
      | body                 | {"tide": "ebb"}  |
    Then the response status is 404

  Scenario: the foreman-data tool reads and merges an instance's data
    Given the crew "bartholomew" allows tools: "foreman/data"
    When isaac is run with "foreman start lighthouse-watch beacon-7 --data {:tide \"low\"}"
    Given the following model responses are queued:
      | model | tool_call     | arguments                                                                              |
      | echo  | foreman__data | {"machine": "lighthouse-watch", "instance": "beacon-7", "set": {"tide": "slack"}}      |
      | echo  | foreman__data | {"machine": "lighthouse-watch", "instance": "beacon-7"}                                |
      | model | type          | content                                                                                |
      | echo  | text          | Tide noted.                                                                            |
    When the user sends "note the slack tide" on session "lamp-room"
    Then session "lamp-room" has transcript matching:
      | type    | message.role | message.content          |
      | message | toolResult   | #"(?s).*tide.*slack.*"   |
    When isaac is run with "foreman data lighthouse-watch beacon-7"
    Then the stdout matches:
      | pattern           |
      | :tide\s+"slack"   |
