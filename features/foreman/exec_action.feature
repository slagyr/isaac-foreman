Feature: Foreman — exec actions (isaac-c1hy)
  An :exec action runs a command when its transition fires:
  {:type :exec :command [...] :output {:data :<key>} :cwd "..." :timeout <seconds>}.
  :command is an argv vector (no shell unless you name one) whose elements
  are templated like prompts. With :output {:data :<key>} its stdout lands
  in the instance data under that key — a JSON or EDN map or vector is parsed, anything else is kept as
  trimmed text — before the next action in the list runs, so
  [:load-bean :work] loads first and prompts second. A non-zero exit or a
  timeout stops the remaining actions and fires the event
  :<action>-failed with {:exec {:action :exit :stderr :timeout}}, which the
  machine may handle like any other event. Foreman is the orchestration
  layer; machine config is an admin surface, so declared commands are
  allowed here (never in the agent's drive or hail delivery).

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
  Scenario: an exec action loads data that the next action's prompt uses
    Given the isaac file "config/machines/lighthouse-watch.edn" exists with:
      """
      {:initial :dark
       :actions {:load-log  {:type    :exec
                             :command ["printf" "{\"title\": \"Fix the lamp\", \"status\": \"todo\"}"]
                             :output    {:data :log}}
                 :tend-lamp {:type        :turn
                             :frequencies {:session "lamp-room"}
                             :prompt      "Work {{instance}}: {{data.log.title}} ({{data.log.status}})."}}
       :transitions [{:start :dark    :event :dusk :end :tending :actions [:load-log :tend-lamp]}
                     {:start :tending :event :lit  :end :lit}]}
      """
    And the following model responses are queued:
      | type | content   | model |
      | text | On it.    | echo  |
    When isaac is run with "foreman start lighthouse-watch beacon-7"
    And isaac is run with "foreman signal lighthouse-watch beacon-7 dusk"
    Then session "lamp-room" has transcript matching:
      | type    | message.role | message.content                   |
      | message | user         | Work beacon-7: Fix the lamp (todo). |
    When isaac is run with "foreman data lighthouse-watch beacon-7"
    Then the stdout matches:
      | pattern                  |
      | :title\s+"Fix the lamp"  |

  @wip
  Scenario: the command and cwd are templated from the data; plain output is kept as text
    Given the isaac file "config/machines/lighthouse-watch.edn" exists with:
      """
      {:initial :dark
       :actions {:echo-tide {:type :exec :command ["printf" "%s tide" "{{data.tide}}"] :output {:data :heard}}
                 :where     {:type :exec :command ["pwd"] :cwd "{{data.dir}}" :output {:data :cwd}}}
       :transitions [{:start :dark :event :dusk :end :lit :actions [:echo-tide :where]}]}
      """
    When isaac is run with "foreman start lighthouse-watch beacon-7 --data {:tide \"high\" :dir \"/\"}"
    And isaac is run with "foreman signal lighthouse-watch beacon-7 dusk"
    Then the exit code is 0
    When isaac is run with "foreman data lighthouse-watch beacon-7"
    Then the stdout matches:
      | pattern               |
      | :heard\s+"high tide"  |
      | :cwd\s+"/"            |

  @wip
  Scenario: a failing command stops the remaining actions and fires <action>-failed
    Given the isaac file "config/machines/lighthouse-watch.edn" exists with:
      """
      {:initial :dark
       :actions {:load-log  {:type :exec :command ["sh" "-c" "echo log is missing >&2; exit 3"] :output {:data :log}}
                 :tend-lamp {:type :turn :frequencies {:session "lamp-room"} :prompt "Work {{instance}}."}}
       :transitions [{:start :dark    :event :dusk            :end :tending  :actions [:load-log :tend-lamp]}
                     {:start :tending :event :load-log-failed :end :stranded}]}
      """
    When isaac is run with "foreman start lighthouse-watch beacon-7"
    And isaac is run with "foreman signal lighthouse-watch beacon-7 dusk"
    And isaac is run with "foreman status lighthouse-watch beacon-7"
    Then the stdout matches:
      | pattern                                   |
      | beacon-7\s+stranded                       |
      | load-log-failed: tending -> stranded      |
    And the stdout does not contain "tend-lamp (turn)"
    When isaac is run with "foreman data lighthouse-watch beacon-7"
    Then the stdout matches:
      | pattern                     |
      | :exit\s+3                   |
      | :stderr\s+"log is missing"  |

  @wip
  Scenario: a command that outlives its timeout is stopped and fails
    Given the isaac file "config/machines/lighthouse-watch.edn" exists with:
      """
      {:initial :dark
       :actions {:wait-tide {:type :exec :command ["sleep" "5"] :timeout 1 :output {:data :tide}}}
       :transitions [{:start :dark    :event :dusk             :end :waiting :actions [:wait-tide]}
                     {:start :waiting :event :wait-tide-failed :end :stranded}]}
      """
    When isaac is run with "foreman start lighthouse-watch beacon-7"
    And isaac is run with "foreman signal lighthouse-watch beacon-7 dusk"
    And isaac is run with "foreman status lighthouse-watch beacon-7"
    Then the stdout matches:
      | pattern                                 |
      | beacon-7\s+stranded                     |
      | wait-tide-failed: waiting -> stranded   |
    When isaac is run with "foreman data lighthouse-watch beacon-7"
    Then the stdout matches:
      | pattern           |
      | :timeout\s+true   |
