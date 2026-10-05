(ns isaac.foreman.core-spec
  (:require
    [clojure.string :as str]
    [isaac.foundation.config.loader :as loader]
    [isaac.foundation.module.discovery :as discovery]
    [isaac.foreman.core :as sut]
    [isaac.foundation.fs :as fs]
    [isaac.foundation.nexus :as nexus]
    [isaac.foreman.store :as store]
    [isaac.agent.turn.submit :as submit]
    [isaac.agent.turn.worker :as worker]
    [speclj.core :refer :all]))

(def lighthouse
  {:initial     :dark
   :actions     {:light-lamp {:type :log :message "lamp lit"}
                 :douse-lamp {:type :log :message "lamp doused"}}
   :transitions [{:start :dark :event :dusk :end :lit :actions [:light-lamp]}
                 {:start :lit :event :dawn :end :dark :actions [:douse-lamp]}]})

(describe "isaac.foreman.core"

  (with mem (fs/mem-fs))
  (with root "/isaac-state")
  (with now (java.time.Instant/parse "2026-03-01T18:00:00Z"))

  (around [example]
    (nexus/-with-nested-nexus {:fs @mem}
      (fs/mkdirs @mem (str @root "/config"))
      (fs/spit @mem (str @root "/config/isaac.edn")
               (pr-str {:defaults {:frequencies {:crew "main"} :crew {:model "grover"}}
                        :crew {"main" {}} :models {"grover" {:model "echo" :provider :grover :context-window 32768}}
                        :providers {"grover" {}} :machines {"lighthouse-watch" lighthouse}}))
      (example)))

  (it "start prints instance: initial-state"
    (let [out (with-out-str
                (sut/start! {:fs @mem :root @root :machine "lighthouse-watch"
                             :id "beacon-7" :now @now}))]
      (should (str/includes? out "beacon-7: dark"))))

  (it "signal prints instance: from -> to (event) and executes :log"
    (sut/start! {:fs @mem :root @root :machine "lighthouse-watch"
                 :id "beacon-7" :now @now})
    (let [out (with-out-str
                (sut/signal! {:fs @mem :root @root :machine "lighthouse-watch"
                              :id "beacon-7" :event :dusk :now @now}))]
      (should (str/includes? out "lamp lit"))
      (should (str/includes? out "beacon-7: dark -> lit (dusk)"))))

  (it "seeds instance data and merges handled events without changing history"
    (let [opts {:fs @mem :root @root :machine "lighthouse-watch" :id "beacon-7"}]
      (with-out-str (sut/start! (assoc opts :data {:keeper {:name "Atticus"} :tide "low"})))
      (with-out-str (sut/signal! (assoc opts :event :dusk :data {:keeper nil :tide "high"})))
      (should= {:tide "high"} (:data (store/get-instance opts)))
      (should= {:keeper nil :tide "high"} (:data (last (store/history opts))))))

  (it "reads and merges data without a transition, rejecting unknown instances"
    (let [opts {:fs @mem :root @root :machine "lighthouse-watch" :id "beacon-7"}]
      (with-out-str (sut/start! (assoc opts :data {:tide "low" :keeper "Ada"})))
      (should= {:tide "high"} (sut/data! (assoc opts :set {:tide "high" :keeper nil})))
      (should= {:tide "high"} (sut/data! opts))
      (should= [] (store/history opts))
      (should-throw Exception #"unknown instance"
        (sut/data! (assoc opts :id "ghost-9" :set {:tide "high"})))))

  (it "a deliberate signal with no transition is refused; the instance stays put"
    (sut/start! {:fs @mem :root @root :machine "lighthouse-watch"
                 :id "beacon-7" :now @now})
    (should-throw Exception #"no transition for earthquake from dark"
      (with-out-str
        (sut/signal! {:fs @mem :root @root :machine "lighthouse-watch"
                      :id "beacon-7" :event :earthquake :now @now})))
    (should= :dark (:state (store/get-instance {:fs @mem :root @root :machine "lighthouse-watch" :id "beacon-7"}))))

  (it "does not merge data on a refused event"
    (let [opts {:fs @mem :root @root :machine "lighthouse-watch" :id "beacon-7"}]
      (with-out-str (sut/start! (assoc opts :data {:tide "low"})))
      (should-throw Exception #"no transition"
        (with-out-str (sut/signal! (assoc opts :event :earthquake :data {:tide "high"}))))
      (should= {:tide "low"} (sut/data! opts))))

  (it "an observation with no transition stays quiet; the instance stays put"
    (sut/start! {:fs @mem :root @root :machine "lighthouse-watch"
                 :id "beacon-7" :now @now})
    (with-out-str
      (sut/signal! {:fs @mem :root @root :machine "lighthouse-watch"
                    :id "beacon-7" :event :earthquake :now @now :source :observer}))
    (should= :dark (:state (store/get-instance {:fs @mem :root @root :machine "lighthouse-watch" :id "beacon-7"}))))

  (it "unknown instance throws with unknown instance"
    (should-throw Exception #"unknown instance"
      (sut/signal! {:fs @mem :root @root :machine "lighthouse-watch"
                    :id "ghost-9" :event :dusk :now @now})))

  )

(describe "durable event intake"
  (with mem (fs/mem-fs))
  (with root "/isaac-state")
  (around [example]
    (nexus/-with-nested-nexus {:fs @mem}
      (fs/mkdirs @mem (str @root "/config"))
      (fs/spit @mem (str @root "/config/isaac.edn")
               (pr-str {:defaults {:frequencies {:crew "main"} :crew {:model "grover"}}
                        :crew {"main" {}} :models {"grover" {:model "echo" :provider :grover :context-window 32768}}
                        :providers {"grover" {}} :machines {"lighthouse-watch" lighthouse}}))
      (with-out-str (sut/start! {:fs @mem :root @root :machine "lighthouse-watch" :id "beacon-7"}))
      (example)))
  (it "acknowledges a duplicate without transitioning again"
    (let [opts {:fs @mem :root @root :machine "lighthouse-watch" :id "beacon-7" :event :dusk :event-id "tide-42"}]
      (with-out-str (sut/signal! opts))
      (let [result (atom nil)]
        (with-out-str (reset! result (sut/signal! (assoc opts :source :http))))
        (should (:duplicate @result)))))
  (it "applies unconsumed received events before a new event"
    (fs/spit @mem (str @root "/foreman/lighthouse-watch/beacon-7.events.ednl")
             (str (pr-str {:type :received :id "tide-7" :machine "lighthouse-watch" :instance "beacon-7"
                           :event :dusk :source :http :at "2026-03-01T18:00:00Z"}) "\n"))
    (let [out (with-out-str (sut/signal! {:fs @mem :root @root :machine "lighthouse-watch"
                                          :id "beacon-7" :event :dawn :event-id "tide-8"}))]
      (should (str/includes? out "dark -> lit"))
      (should (str/includes? out "lit -> dark")))))

(describe "Foreman turn actions"
  (with mem (fs/mem-fs))
  (with root "/isaac-state")
  (with table {:initial :dark
               :actions {:tend-lamp {:type :turn :frequencies {:session "lamp-room"}
                                      :resource-pools ["dock"]
                                      :prompt "Light {{instance}} at {{machine}}: {{data.tide}}"}}
               :transitions [{:start :dark :event :dusk :end :tending :actions [:tend-lamp]}]})
  (around [example]
    (nexus/-with-nested-nexus {:fs @mem}
      (fs/mkdirs @mem (str @root "/config"))
      (fs/spit @mem (str @root "/config/isaac.edn")
               (pr-str {:defaults {:frequencies {:crew "main"} :crew {:model "grover"}}
                        :crew {"main" {}} :models {"grover" {:model "echo" :provider :grover :context-window 32768}}
                        :providers {"grover" {}} :machines {"lighthouse-watch" @table}}))
      (with-out-str (sut/start! {:fs @mem :root @root :machine "lighthouse-watch" :id "beacon-7"}))
      (example)))

  (it "persists the transition before submitting a keyed turn with event data"
    (let [request (atom nil)]
      (with-redefs [submit/submit! (fn [req]
                                     (should= :tending (:state (store/get-instance (assoc req :id "beacon-7"))))
                                     (reset! request req)
                                     {:id "turn-17"})]
        (with-out-str (sut/signal! {:fs @mem :root @root :machine "lighthouse-watch" :id "beacon-7"
                                    :event :dusk :event-id "tide-1" :data {:tide "high"}})))
      (should= "lighthouse-watch/beacon-7/tide-1/tend-lamp" (:key @request))
      (should= "Light beacon-7 at lighthouse-watch: high" (:prompt @request))
      (should= {:session "lamp-room"} (:frequencies @request))
      (should= ["dock"] (:resource-pools @request))
      (should= [[:foreman "lighthouse-watch" "beacon-7"]] (:observers @request))
      (should= {:kind :foreman :machine "lighthouse-watch" :instance "beacon-7"}
               (:origin @request))
      (should (str/includes? (with-out-str (sut/status {:fs @mem :root @root :machine "lighthouse-watch" :id "beacon-7"}))
                             "tend-lamp (turn) submitted turn-17"))))

  (it "retries the originally enqueued prompt even if data changes"
    (let [opts {:fs @mem :root @root :machine "lighthouse-watch" :id "beacon-7"}
          prompts (atom [])]
      (sut/data! (assoc opts :set {:tide "low"}))
      (with-redefs [submit/submit! (fn [req]
                                     (swap! prompts conj (:prompt req))
                                     (throw (ex-info "offline" {})))]
        (with-out-str (sut/signal! (assoc opts :event :dusk :data {:tide "high"}))))
      (sut/data! (assoc opts :set {:tide "ebb"}))
      (fs/spit @mem (str @root "/config/isaac.edn")
               (pr-str {:defaults {:frequencies {:crew "main"} :crew {:model "grover"}}
                        :crew {"main" {}} :models {"grover" {:model "echo" :provider :grover :context-window 32768}}
                        :providers {"grover" {}}
                        :machines {"lighthouse-watch" (assoc-in @table [:actions :tend-lamp :prompt]
                                                                  "Changed template: {{data.tide}}")}}))
      (with-redefs [submit/submit! (fn [req]
                                     (swap! prompts conj (:prompt req))
                                     {:id "turn-18"})]
        (with-out-str (sut/retry! opts)))
      (should= ["Light beacon-7 at lighthouse-watch: high"
                "Light beacon-7 at lighthouse-watch: high"] @prompts)))

  (it "keeps a submitted action after a turn signals during the wake"
    (let [table (assoc @table :transitions (conj (:transitions @table)
                                               {:start :tending :event :lit :end :lit}))]
      (fs/spit @mem (str @root "/config/isaac.edn")
               (pr-str {:defaults {:frequencies {:crew "main"} :crew {:model "grover"}}
                         :crew {"main" {}} :models {"grover" {:model "echo" :provider :grover :context-window 32768}}
                         :providers {"grover" {}} :machines {"lighthouse-watch" table}})))
    (with-redefs [submit/submit! (fn [_] {:id "turn-17"})
                  worker/tick! (fn []
                                 (with-out-str (sut/signal! {:fs @mem :root @root :machine "lighthouse-watch"
                                                              :id "beacon-7" :event :lit :source :observer})))]
      (with-out-str (sut/signal! {:fs @mem :root @root :machine "lighthouse-watch"
                                  :id "beacon-7" :event :dusk})))
    (should= "turn-17" (:request-id (first (:pending-actions
                                            (store/get-instance {:fs @mem :root @root :machine "lighthouse-watch" :id "beacon-7"}))))))

  (it "wakes a newly accepted turn after persisting its request id"
    (let [at-wake (atom nil)]
      (with-redefs [submit/submit! (fn [_] {:id "turn-17"})
                    worker/tick! (fn [] (reset! at-wake (:pending-actions
                                                         (store/get-instance {:fs @mem :root @root :machine "lighthouse-watch" :id "beacon-7"}))))]
        (with-out-str (sut/signal! {:fs @mem :root @root :machine "lighthouse-watch"
                                    :id "beacon-7" :event :dusk})))
      (should= "turn-17" (:request-id (first @at-wake)))))

  (it "retains failed pending actions and retries with the same key"
    (with-redefs [submit/submit! (fn [_] (throw (ex-info "unknown resource pool drydock" {})))]
      (with-out-str (sut/signal! {:fs @mem :root @root :machine "lighthouse-watch" :id "beacon-7"
                                  :event :dusk :event-id "tide-1"})))
    (let [opts {:fs @mem :root @root :machine "lighthouse-watch" :id "beacon-7"}]
      (should (str/includes? (with-out-str (sut/status opts)) "failed: unknown resource pool drydock"))
      (with-redefs [submit/submit! (fn [req]
                                     (should= "lighthouse-watch/beacon-7/tide-1/tend-lamp" (:key req))
                                     {:id "turn-17"})]
        (with-out-str (sut/retry! opts)))
      (should (str/includes? (with-out-str (sut/status opts)) "submitted turn-17"))))
  )

(describe "Foreman restart recovery"
  (with mem (fs/mem-fs))
  (around [example]
    (nexus/-with-nested-nexus {:fs @mem}
      (fs/mkdirs @mem "/isaac-state/config")
      (fs/spit @mem "/isaac-state/config/isaac.edn"
               (pr-str {:defaults {:frequencies {:crew "main"} :crew {:model "grover"}}
                        :crew {"main" {}} :models {"grover" {:model "echo" :provider :grover :context-window 32768}}
                        :providers {"grover" {}} :machines {"lighthouse-watch" lighthouse}}))
      (example)))
  (it "resumes all instances left with acknowledged but unconsumed events"
    (doseq [id ["beacon-7" "beacon-9"]]
      (with-out-str (sut/start! {:fs @mem :root "/isaac-state" :machine "lighthouse-watch" :id id}))
      (fs/spit @mem (str "/isaac-state/foreman/lighthouse-watch/" id ".events.ednl")
               (str (pr-str {:type :received :id (str id "-dusk") :machine "lighthouse-watch"
                             :instance id :event :dusk :source :http :at "2026-03-01T18:00:00Z"}) "\n")))
    (with-out-str (sut/resume! {:fs @mem :root "/isaac-state"}))
    (should= ["beacon-7" "beacon-9"]
             (mapv :id (sut/list-instances {:fs @mem :root "/isaac-state"
                                             :machine "lighthouse-watch" :state :lit})))))

(describe "Foreman contributed actions"
  (with mem (fs/mem-fs))
  (with opts {:fs @mem :root "/isaac-state" :machine "lighthouse-watch" :id "beacon-7"})
  (around [example]
    (nexus/-with-nested-nexus {:fs @mem}
      (fs/mkdirs @mem "/isaac-state/config")
      (example)))

  (it "stops after a failed contributed action and signals its failure under :failed"
    (let [builtin-index discovery/builtin-index
          table {:initial :dark
                 :actions {:ring-bell {:type :chime :times 3}
                           :after {:type :log :message "should not ring"}}
                 :transitions [{:start :dark :event :dusk :end :lit :actions [:ring-bell :after]}
                               {:start :lit :event :ring-bell-failed :end :silent}]}
          calls (atom [])]
      (fs/spit @mem "/isaac-state/config/isaac.edn"
               (pr-str {:defaults {:frequencies {:crew "main"} :crew {:model "grover"}}
                        :crew {"main" {}} :models {"grover" {:model "echo" :provider :grover :context-window 32768}}
                        :providers {"grover" {}} :machines {"lighthouse-watch" table}}))
      (with-redefs [discovery/builtin-index (fn [] (assoc (builtin-index) :marigold.chime
                                                        {:manifest {:id :marigold.chime
                                                                    :isaac.foreman/action {:chime {:handler 'isaac.foreman.action/log!
                                                                                                   :action-spec {:times {:type :int}}}}}}))
                    isaac.foreman.action/run! (fn [type ctx _]
                                                 (swap! calls conj type)
                                                 (when (= type :chime)
                                                   {:failed {:action (:action ctx) :reason :broken}}))]
        (with-out-str (sut/start! @opts))
        (with-out-str (sut/signal! (assoc @opts :event :dusk))))
      (should= [:chime] @calls)
      (should= :silent (:state (store/get-instance @opts)))
      (should= {:action :ring-bell :reason :broken}
               (get-in (store/get-instance @opts) [:data :failed]))))
  )

(describe "Foreman exec actions"
  (with mem (fs/mem-fs))
  (with root "/isaac-state")
  (with opts {:fs @mem :root @root :machine "lighthouse-watch" :id "beacon-7"})
  (around [example]
    (nexus/-with-nested-nexus {:fs @mem}
      (fs/mkdirs @mem (str @root "/config"))
      (example)))

  (it "loads JSON before rendering a subsequent turn"
    (let [table {:initial :dark
                 :actions {:load-log {:type :exec :command ["printf" "{\"title\":\"Fix the lamp\"}"] :output {:data :log}}
                           :tend-lamp {:type :turn :frequencies {:session "lamp-room"}
                                       :prompt "Work {{data.log.title}}"}}
                 :transitions [{:start :dark :event :dusk :end :lit :actions [:load-log :tend-lamp]}]}
          request (atom nil)]
      (fs/spit @mem (str @root "/config/isaac.edn") (pr-str {:defaults {:frequencies {:crew "main"} :crew {:model "grover"}}
                              :crew {"main" {}} :models {"grover" {:model "echo" :provider :grover :context-window 32768}}
                              :providers {"grover" {}} :machines {"lighthouse-watch" table}}))
      (with-out-str (sut/start! @opts))
      (with-redefs [submit/submit! (fn [req] (reset! request req) {:id "turn-17"})
                    worker/tick! (fn [])]
        (with-out-str (sut/signal! (assoc @opts :event :dusk))))
      (should= {:title "Fix the lamp"} (get-in (store/get-instance @opts) [:data :log]))
      (should= "Work Fix the lamp" (:prompt @request))))

  (it "templates the argv and cwd, retaining unstructured stdout as text"
    (let [table {:initial :dark
                 :actions {:echo {:type :exec :command ["printf" "%s tide" "{{data.tide}}"] :output {:data :heard}}
                           :where {:type :exec :command ["pwd"] :cwd "{{data.dir}}" :output {:data :cwd}}}
                 :transitions [{:start :dark :event :dusk :end :lit :actions [:echo :where]}]}]
      (fs/spit @mem (str @root "/config/isaac.edn") (pr-str {:defaults {:frequencies {:crew "main"} :crew {:model "grover"}}
                              :crew {"main" {}} :models {"grover" {:model "echo" :provider :grover :context-window 32768}}
                              :providers {"grover" {}} :machines {"lighthouse-watch" table}}))
      (with-out-str (sut/start! (assoc @opts :data {:tide "high" :dir "/"})))
      (with-out-str (sut/signal! (assoc @opts :event :dusk)))
      (should= "high tide" (get-in (store/get-instance @opts) [:data :heard]))
      (should= "/" (get-in (store/get-instance @opts) [:data :cwd]))))

  (it "stops the list on nonzero exit and signals failure with metadata"
    (let [table {:initial :dark
                 :actions {:load-log {:type :exec :command ["sh" "-c" "echo log is missing >&2; exit 3"] :output {:data :log}}
                           :later {:type :exec :command ["printf" "unexpected"] :output {:data :later}}}
                 :transitions [{:start :dark :event :dusk :end :tending :actions [:load-log :later]}
                               {:start :tending :event :load-log-failed :end :stranded}]}]
      (fs/spit @mem (str @root "/config/isaac.edn") (pr-str {:defaults {:frequencies {:crew "main"} :crew {:model "grover"}}
                              :crew {"main" {}} :models {"grover" {:model "echo" :provider :grover :context-window 32768}}
                              :providers {"grover" {}} :machines {"lighthouse-watch" table}}))
      (with-out-str (sut/start! @opts))
      (with-out-str (sut/signal! (assoc @opts :event :dusk)))
      (should= :stranded (:state (store/get-instance @opts)))
      (should= nil (get-in (store/get-instance @opts) [:data :later]))
      (should= {:action :load-log :exit 3 :stderr "log is missing" :timeout false}
               (get-in (store/get-instance @opts) [:data :exec]))))

  (it "kills a command on timeout and signals failure"
    (let [table {:initial :dark
                 :actions {:wait-tide {:type :exec :command ["sleep" "5"] :timeout 1 :output {:data :tide}}}
                 :transitions [{:start :dark :event :dusk :end :waiting :actions [:wait-tide]}
                               {:start :waiting :event :wait-tide-failed :end :stranded}]}]
      (fs/spit @mem (str @root "/config/isaac.edn") (pr-str {:defaults {:frequencies {:crew "main"} :crew {:model "grover"}}
                              :crew {"main" {}} :models {"grover" {:model "echo" :provider :grover :context-window 32768}}
                              :providers {"grover" {}} :machines {"lighthouse-watch" table}}))
      (with-out-str (sut/start! @opts))
      (with-out-str (sut/signal! (assoc @opts :event :dusk)))
      (should= :stranded (:state (store/get-instance @opts)))
      (should= true (get-in (store/get-instance @opts) [:data :exec :timeout]))))
  )
