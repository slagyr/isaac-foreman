Feature: Foreman over HTTP (isaac-v557)
  A dispatcher off the host drives Foreman the way it sends hails: over
  HTTP with a scoped principal, no shell needed. POST /foreman/instances
  starts an instance (and may fire its first event in the same call);
  GET /foreman/instances/<machine> lists instances; GET
  /foreman/instances/<machine>/<id> shows one, with its data, pending
  actions and history. Starting, signaling (POST /foreman/events) and
  setting data need :foreman/write; reading needs :foreman/read.

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

  Scenario: POST /foreman/instances starts an instance with its data
    When a POST request is made to "/foreman/instances":
      | key                  | value                                                                             |
      | header.Content-Type  | application/json                                                                  |
      | header.Authorization | Bearer secret123                                                                  |
      | body                 | {"machine": "lighthouse-watch", "instance": "beacon-7", "data": {"keeper": "Ada"}} |
    Then the response status is 201
    And the response body has "state" equal to "dark"
    When isaac is run with "foreman status lighthouse-watch beacon-7"
    Then the stdout matches:
      | pattern                |
      | beacon-7\s+dark        |
      | :keeper\s+"Ada"        |

  Scenario: a start may fire the first event in the same call
    When a POST request is made to "/foreman/instances":
      | key                  | value                                                                   |
      | header.Content-Type  | application/json                                                        |
      | header.Authorization | Bearer secret123                                                        |
      | body                 | {"machine": "lighthouse-watch", "instance": "beacon-7", "event": "dusk"} |
    Then the response status is 201
    And the response body has "state" equal to "lit"
    When isaac is run with "foreman status lighthouse-watch beacon-7"
    Then the stdout matches:
      | pattern                                  |
      | beacon-7\s+lit                           |
      | dusk: dark -> lit\s+\[[^\]]+\] via http  |

  Scenario: a second start of the same instance is refused, and so is an unknown machine
    When isaac is run with "foreman start lighthouse-watch beacon-7"
    And a POST request is made to "/foreman/instances":
      | key                  | value                                                   |
      | header.Content-Type  | application/json                                        |
      | header.Authorization | Bearer secret123                                        |
      | body                 | {"machine": "lighthouse-watch", "instance": "beacon-7"} |
    Then the response status is 409
    And the response body has a "error" key
    When a POST request is made to "/foreman/instances":
      | key                  | value                                                |
      | header.Content-Type  | application/json                                     |
      | header.Authorization | Bearer secret123                                     |
      | body                 | {"machine": "foghorn-watch", "instance": "beacon-7"} |
    Then the response status is 404
    And the response body has a "error" key

  Scenario: GET /foreman/instances/<machine>/<id> shows the state, data and history
    When isaac is run with "foreman start lighthouse-watch beacon-7 --data {:keeper \"Ada\"}"
    And isaac is run with "foreman signal lighthouse-watch beacon-7 dusk"
    And a GET request is made to "/foreman/instances/lighthouse-watch/beacon-7":
      | key                  | value            |
      | header.Authorization | Bearer secret123 |
    Then the response status is 200
    And the response body has "state" equal to "lit"
    And the response body has "data.keeper" equal to "Ada"
    And the response body has "history[0].event" equal to "dusk"
    And the response body has "history[0].from" equal to "dark"
    And the response body has "history[0].to" equal to "lit"
    And the response body has a "since" key
    When a GET request is made to "/foreman/instances/lighthouse-watch/ghost-9":
      | key                  | value            |
      | header.Authorization | Bearer secret123 |
    Then the response status is 404

  Scenario: GET /foreman/instances/<machine> lists its instances
    When isaac is run with "foreman start lighthouse-watch beacon-7"
    And isaac is run with "foreman signal lighthouse-watch beacon-7 dusk"
    And isaac is run with "foreman start lighthouse-watch beacon-9"
    And a GET request is made to "/foreman/instances/lighthouse-watch":
      | key                  | value            |
      | header.Authorization | Bearer secret123 |
    Then the response status is 200
    And the response body has "instances[0].id" equal to "beacon-7"
    And the response body has "instances[0].state" equal to "lit"
    And the response body has "instances[1].id" equal to "beacon-9"
    And the response body has "instances[1].state" equal to "dark"

  Scenario: writing needs foreman/write and reading needs foreman/read
    Given principal "dispatcher" is configured with secret "dispatch-secret" and scopes "foreman/write"
    And principal "watcher" is configured with secret "watch-secret" and scopes "foreman/read"
    And principal "hailer" is configured with secret "hail-secret" and scopes "hail/send"
    When a POST request is made to "/foreman/instances":
      | key                  | value                                                   |
      | header.Content-Type  | application/json                                        |
      | header.Authorization | Bearer dispatch-secret                                  |
      | body                 | {"machine": "lighthouse-watch", "instance": "beacon-7"} |
    Then the response status is 201
    When a POST request is made to "/foreman/events":
      | key                  | value                                                                    |
      | header.Content-Type  | application/json                                                         |
      | header.Authorization | Bearer dispatch-secret                                                   |
      | body                 | {"machine": "lighthouse-watch", "instance": "beacon-7", "event": "dusk"} |
    Then the response status is 202
    When a GET request is made to "/foreman/instances/lighthouse-watch/beacon-7":
      | key                  | value                  |
      | header.Authorization | Bearer dispatch-secret |
    Then the response status is 403
    When a GET request is made to "/foreman/instances/lighthouse-watch/beacon-7":
      | key                  | value               |
      | header.Authorization | Bearer watch-secret |
    Then the response status is 200
    And the response body has "state" equal to "lit"
    When a POST request is made to "/foreman/events":
      | key                  | value                                                                    |
      | header.Content-Type  | application/json                                                         |
      | header.Authorization | Bearer watch-secret                                                      |
      | body                 | {"machine": "lighthouse-watch", "instance": "beacon-7", "event": "dawn"} |
    Then the response status is 403
    When a POST request is made to "/foreman/instances":
      | key                  | value                                                   |
      | header.Content-Type  | application/json                                        |
      | header.Authorization | Bearer hail-secret                                      |
      | body                 | {"machine": "lighthouse-watch", "instance": "beacon-9"} |
    Then the response status is 403
    When a GET request is made to "/foreman/instances/lighthouse-watch":
      | key                  | value              |
      | header.Authorization | Bearer hail-secret |
    Then the response status is 403
