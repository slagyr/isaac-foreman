(ns isaac.foreman.test-runner-spec
  (:require
    [clojure.string :as str]
    [gherclj.parser :as parser]
    [isaac.foreman.test-runner :as sut]
    [isaac.foundation.fs :as fs]
    [isaac.foundation.nexus :as nexus]
    [speclj.core :refer :all]))

(def lighthouse
  {:initial     :dark
   :actions     {:light-lamp {:type :turn :frequencies {:crew "bartholomew"}
                              :prompt "Light the lamp at {{instance}}."}
                 :douse-lamp {:type :turn :frequencies {:crew "bartholomew"}
                              :prompt "Douse the lamp at {{instance}}."}
                 :log-watch  {:type :log :message "watch changed"}}
   :transitions [{:start :dark :event :dusk :end :lit  :actions [:light-lamp :log-watch]}
                 {:start :lit  :event :dawn :end :dark :actions [:douse-lamp :log-watch]}]})

(def cfg {:machines {"lighthouse-watch" lighthouse}})

(defn- given [text] {:type :given :text text})
(defn- when-step [text] {:type :when :text text})
(defn- then [text] {:type :then :text text})
(defn- and-step [text] {:type :and :text text})

(defn- run [background steps]
  (sut/run-scenario cfg background {:scenario "scenario" :steps steps}))

(describe "isaac.foreman.test-runner"

  (context "resolve-kinds"

    (it "carries And forward from the previous Given/When/Then"
      (let [resolved (sut/resolve-kinds [(given "a") (and-step "b") (when-step "c")
                                          (and-step "d") (then "e") (and-step "f")])]
        (should= [:given :given :when :when :then :then] (mapv :kind resolved))))

    (it "defaults a leading And/But to :given"
      (should= [:given] (mapv :kind (sut/resolve-kinds [(and-step "x")]))))

    )

  (context "defaults"

    (it "defaults instance to test-1 and state to the machine's :initial"
      (should= {:name "scenario" :pass? true}
               (run [(given "the \"lighthouse-watch\" machine")]
                    [(when-step "\"dusk\" is signaled")
                     (then "the state is \"lit\"")])))

    (it "Given the state is overrides the default"
      (should= {:name "scenario" :pass? true}
               (run [(given "the \"lighthouse-watch\" machine")
                     (given "the state is \"lit\"")]
                    [(when-step "\"dawn\" is signaled")
                     (then "the state is \"dark\"")])))

    (it "fails with unknown machine when the machine name is not configured"
      (let [result (run [(given "the \"no-such-machine\" machine")] [])]
        (should-not (:pass? result))
        (should (str/includes? (:mismatch result) "unknown machine: no-such-machine"))))

    )

  (context "state check"

    (it "fails with the step text and expected-vs-actual on a mismatch"
      (let [result (run [(given "the \"lighthouse-watch\" machine")]
                         [(when-step "\"dusk\" is signaled")
                          (then "the state is \"dark\"")])]
        (should-not (:pass? result))
        (should= "Then the state is \"dark\"" (:step result))
        (should= "expected dark, got lit" (:mismatch result))))

    )

  (context "unhandled signals"

    (it "passes when the signal is unhandled, and later checks run normally"
      (should (:pass? (run [(given "the \"lighthouse-watch\" machine")]
                            [(when-step "\"dawn\" is signaled")
                             (then "the signal is unhandled")
                             (and-step "the state is \"dark\"")
                             (and-step "there are no actions")]))))

    (it "fails an unexpected check, naming the event and the state"
      (let [result (run [(given "the \"lighthouse-watch\" machine")]
                         [(when-step "\"dawn\" is signaled")
                          (then "the state is \"lit\"")])]
        (should-not (:pass? result))
        (should= "no transition for dawn from dark" (:mismatch result))))

    (it "fails the unhandled check itself when the signal was actually handled"
      (let [result (run [(given "the \"lighthouse-watch\" machine")]
                         [(when-step "\"dusk\" is signaled")
                          (then "the signal is unhandled")])]
        (should-not (:pass? result))))

    )

  (context "actions"

    (it "are: checks exact order and fails on a mismatch"
      (should (:pass? (run [(given "the \"lighthouse-watch\" machine")]
                            [(when-step "\"dusk\" is signaled")
                             (then "the actions are \"light-lamp, log-watch\"")])))
      (should-not (:pass? (run [(given "the \"lighthouse-watch\" machine")]
                                [(when-step "\"dusk\" is signaled")
                                 (then "the actions are \"log-watch, light-lamp\"")]))))

    (it "contain: checks membership"
      (should (:pass? (run [(given "the \"lighthouse-watch\" machine")]
                            [(when-step "\"dusk\" is signaled")
                             (then "the actions contain \"light-lamp\"")])))
      (should-not (:pass? (run [(given "the \"lighthouse-watch\" machine")]
                                [(when-step "\"dusk\" is signaled")
                                 (then "the actions contain \"no-such-action\"")]))))

    (it "there are no actions: passes on empty, fails otherwise"
      (should (:pass? (run [(given "the \"lighthouse-watch\" machine")]
                            [(when-step "\"dawn\" is signaled")
                             (then "the signal is unhandled")
                             (and-step "there are no actions")])))
      (should-not (:pass? (run [(given "the \"lighthouse-watch\" machine")]
                                [(when-step "\"dusk\" is signaled")
                                 (then "there are no actions")]))))

    )

  (context "prompt checks"

    (it "is / contains / matches all pass on the filled prompt"
      (should (:pass? (run [(given "the \"lighthouse-watch\" machine") (given "instance \"beacon-7\"")]
                            [(when-step "\"dusk\" is signaled")
                             (then "the \"light-lamp\" prompt is \"Light the lamp at beacon-7.\"")
                             (and-step "the \"light-lamp\" prompt contains \"beacon-7\"")
                             (and-step "the \"light-lamp\" prompt matches \"Light the lamp at beacon-\\d+\\.\"")]))))

    (it "a miss shows the actual prompt"
      (let [result (run [(given "the \"lighthouse-watch\" machine") (given "instance \"beacon-7\"")]
                         [(when-step "\"dusk\" is signaled")
                          (then "the \"light-lamp\" prompt contains \"beacon-9\"")])]
        (should-not (:pass? result))
        (should (str/includes? (:mismatch result) "Light the lamp at beacon-7."))))

    (it "fails with unknown action when the action isn't defined"
      (let [result (run [(given "the \"lighthouse-watch\" machine")]
                         [(when-step "\"dusk\" is signaled")
                          (then "the \"no-such-action\" prompt is \"anything\"")])]
        (should-not (:pass? result))
        (should (str/includes? (:mismatch result) "unknown action"))))

    )

  (context "target is:"

    (it "checks :frequencies and :resource-pools together"
      (let [table  {:initial :dark
                    :actions {:tend-lamp {:type :turn :frequencies {:crew "bartholomew"}
                                          :resource-pools ["dock"] :prompt "..."}}
                    :transitions [{:start :dark :event :dusk :end :tending :actions [:tend-lamp]}]}
            target-cfg {:machines {"lighthouse-watch" table}}
            step   {:type :then :text "the \"tend-lamp\" target is:"
                    :table {:headers ["crew" "bartholomew"] :rows [["resource-pools" "[\"dock\"]"]]}}
            result (sut/run-scenario target-cfg [(given "the \"lighthouse-watch\" machine")]
                                     {:scenario "s" :steps [(when-step "\"dusk\" is signaled") step]})]
        (should (:pass? result))))

    (it "checks a rendered session for the chosen instance"
      (let [table {:initial :dark
                   :actions {:tend-lamp {:type :turn :frequencies {:crew "{{machine}}"
                                                                     :session ["lamp-{{instance}}" "{{data.absent}}"]
                                                                     :create :if-missing} :prompt "..."}}
                   :transitions [{:start :dark :event :dusk :end :tending :actions [:tend-lamp]}]}
            step {:type :then :text "the \"tend-lamp\" target is:"
                  :table {:headers ["crew" "lighthouse-watch"]
                          :rows [["session" "[\"lamp-beacon-7\" \"\"]"] ["create" ":if-missing"]]}}
            result (sut/run-scenario {:machines {"lighthouse-watch" table}}
                                     [(given "the \"lighthouse-watch\" machine") (given "instance \"beacon-7\"")]
                                     {:scenario "s" :steps [(when-step "\"dusk\" is signaled") step]})]
        (should (:pass? result))))

    (it "fails with the expected and actual maps on a mismatch"
      (let [table  {:initial :dark
                    :actions {:tend-lamp {:type :turn :frequencies {:crew "bartholomew"} :prompt "..."}}
                    :transitions [{:start :dark :event :dusk :end :tending :actions [:tend-lamp]}]}
            target-cfg {:machines {"lighthouse-watch" table}}
            step   {:type :then :text "the \"tend-lamp\" target is:"
                    :table {:headers ["crew" "someone-else"]}}
            result (sut/run-scenario target-cfg [(given "the \"lighthouse-watch\" machine")]
                                     {:scenario "s" :steps [(when-step "\"dusk\" is signaled") step]})]
        (should-not (:pass? result))
        (should (str/includes? (:mismatch result) "bartholomew"))
        (should (str/includes? (:mismatch result) "someone-else"))))

    )

  (context "unrecognized steps"

    (it "fails with a clear message naming the step"
      (let [result (run [] [(given "something unknown")])]
        (should-not (:pass? result))
        (should= "unrecognized step: Given something unknown" (:mismatch result))))

    )

  (context "formatting"

    (it "PASS <name> for a passing scenario"
      (should= "PASS dusk lights the lamp" (sut/format-result {:name "dusk lights the lamp" :pass? true})))

    (it "FAIL <name> — <step>: <mismatch> for a failing scenario"
      (should= "FAIL x — Then the state is \"dark\": expected dark, got lit"
               (sut/format-result {:name "x" :pass? false
                                   :step "Then the state is \"dark\"" :mismatch "expected dark, got lit"})))

    (it "exit is 0 only when every scenario passes"
      (should= {:lines ["PASS a" "PASS b"] :exit 0}
               (sut/format-results [{:name "a" :pass? true} {:name "b" :pass? true}]))
      (should= {:lines ["PASS a" "FAIL b — s: m"] :exit 1}
               (sut/format-results [{:name "a" :pass? true}
                                    {:name "b" :pass? false :step "s" :mismatch "m"}])))

    )

  (context "run-scenarios across parsed features"

    (it "runs every scenario in every parsed feature IR, background first"
      (let [ir {:background {:steps [(given "the \"lighthouse-watch\" machine")]}
                :scenarios  [{:scenario "one" :steps [(when-step "\"dusk\" is signaled")
                                                       (then "the state is \"lit\"")]}]}]
        (should= [{:name "one" :pass? true}] (sut/run-scenarios cfg [ir]))))

    )

  (context "gherclj.parser compatibility"

    (it "runs scenarios parsed from real Gherkin text"
      (let [text (str "Feature: lighthouse-watch\n\n"
                      "  Background:\n"
                      "    Given the \"lighthouse-watch\" machine\n"
                      "    And instance \"beacon-7\"\n\n"
                      "  Scenario: dusk lights the lamp\n"
                      "    When \"dusk\" is signaled\n"
                      "    Then the state is \"lit\"\n"
                      "    And the actions are \"light-lamp, log-watch\"\n")
            ir   (parser/parse-feature text)]
        (should= [{:name "dusk lights the lamp" :pass? true}]
                 (sut/run-scenarios cfg [ir]))))

    )

  (context "run-files!"

    (with mem (fs/mem-fs))
    (with root "/isaac-state")

    (around [example]
      (nexus/-with-nested-nexus {:fs @mem}
        (fs/mkdirs @mem (str @root "/config"))
        (fs/spit @mem (str @root "/config/isaac.edn")
                 (pr-str {:defaults {:frequencies {:crew "main"} :crew {:model "grover"}}
                          :crew {"main" {}} :models {"grover" {:model "echo" :provider :grover :context-window 32768}}
                          :providers {"grover" {}} :machines {"lighthouse-watch" lighthouse}}))
        (fs/spit @mem (str @root "/machine-tests/lighthouse.feature")
                 (str "Feature: lighthouse-watch\n\n"
                      "  Background:\n"
                      "    Given the \"lighthouse-watch\" machine\n\n"
                      "  Scenario: dusk lights the lamp\n"
                      "    When \"dusk\" is signaled\n"
                      "    Then the state is \"lit\"\n"))
        (example)))

    (it "resolves relative paths against root, prints PASS lines, and exits 0"
      (let [out (with-out-str
                  (should= 0 (sut/run-files! {:root @root :fs @mem} ["machine-tests/lighthouse.feature"])))]
        (should= "PASS dusk lights the lamp\n" out)))

    (it "exits 1 and prints a FAIL line when a scenario fails"
      (fs/spit @mem (str @root "/machine-tests/lighthouse.feature")
               (str "Feature: lighthouse-watch\n\n"
                    "  Scenario: dusk leaves it dark\n"
                    "    Given the \"lighthouse-watch\" machine\n"
                    "    When \"dusk\" is signaled\n"
                    "    Then the state is \"dark\"\n"))
      (let [out (with-out-str
                  (should= 1 (sut/run-files! {:root @root :fs @mem} ["machine-tests/lighthouse.feature"])))]
        (should (str/includes? out "FAIL dusk leaves it dark"))))

    (it "throws a clear error for a missing file"
      (should-throw Exception #"no such file"
        (sut/run-files! {:root @root :fs @mem} ["machine-tests/missing.feature"])))

    )

  )
