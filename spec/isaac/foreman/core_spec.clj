(ns isaac.foreman.core-spec
  (:require
    [clojure.string :as str]
    [isaac.config.loader :as loader]
    [isaac.foreman.core :as sut]
    [isaac.fs :as fs]
    [isaac.nexus :as nexus]
    [speclj.core :refer :all]))

(def lighthouse
  {:initial     :dark
   :actions     {:light-lamp {:type :log :message "lamp lit"}
                 :douse-lamp {:type :log :message "lamp doused"}}
   :transitions [{:start :dark :event :dusk :end :lit :action [:light-lamp]}
                 {:start :lit :event :dawn :end :dark :action [:douse-lamp]}]})

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

  (it "unhandled events warn on stderr, exit without throwing, leave state"
    (sut/start! {:fs @mem :root @root :machine "lighthouse-watch"
                 :id "beacon-7" :now @now})
    (let [err (java.io.StringWriter.)]
      (binding [*err* err]
        (with-out-str
          (sut/signal! {:fs @mem :root @root :machine "lighthouse-watch"
                        :id "beacon-7" :event :earthquake :now @now})))
      (should (str/includes? (str err) "unhandled: earthquake (state dark)"))))

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
