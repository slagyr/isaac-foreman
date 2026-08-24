(ns isaac.foreman.cli-spec
  (:require
    [clojure.string :as str]
    [isaac.foreman.cli :as sut]
    [isaac.fs :as fs]
    [isaac.nexus :as nexus]
    [speclj.core :refer :all]))

(def lighthouse
  {:initial     :dark
   :actions     {:light-lamp {:type :log :message "lamp lit"}
                 :douse-lamp {:type :log :message "lamp doused"}}
   :transitions [{:start :dark :event :dusk :end :lit :action [:light-lamp]}
                 {:start :lit :event :dawn :end :dark :action [:douse-lamp]}]})

(describe "isaac.foreman.cli"

  (with mem (fs/mem-fs))
  (with root "/isaac-state")
  (with now (java.time.Instant/parse "2026-03-01T18:00:00Z"))

  (around [example]
    (nexus/-with-nested-nexus {:fs @mem}
      (fs/mkdirs @mem (str @root "/config"))
      (fs/spit @mem (str @root "/config/isaac.edn")
               (pr-str {:machines {"lighthouse-watch" lighthouse}}))
      (example)))

  (it "start then signal then status through the CLI dispatcher"
    (let [opts {:fs @mem :root @root :now @now}
          start-out (with-out-str
                      (should= 0 (sut/run (assoc opts :_raw-args ["start" "lighthouse-watch" "beacon-7"]))))
          sig-out   (with-out-str
                      (should= 0 (sut/run (assoc opts :_raw-args ["signal" "lighthouse-watch" "beacon-7" "dusk"]))))
          st-out    (with-out-str
                      (should= 0 (sut/run (assoc opts :_raw-args ["status" "lighthouse-watch" "beacon-7"]))))]
      (should (str/includes? start-out "beacon-7: dark"))
      (should (str/includes? sig-out "beacon-7: dark -> lit (dusk)"))
      (should (re-find #"beacon-7\s+lit\s+since 2026-03-01T18:00" st-out))))

  (it "double-start reports already exists on stderr and exits 1"
    (let [opts {:fs @mem :root @root :now @now}]
      (with-out-str (sut/run (assoc opts :_raw-args ["start" "lighthouse-watch" "beacon-7"])))
      (let [err (java.io.StringWriter.)]
        (binding [*err* err]
          (should= 1 (sut/run (assoc opts :_raw-args ["start" "lighthouse-watch" "beacon-7"]))))
        (should (re-find #"already exists" (str err))))))

  (it "list --state filters by current state"
    (let [opts {:fs @mem :root @root :now @now}]
      (with-out-str
        (sut/run (assoc opts :_raw-args ["start" "lighthouse-watch" "beacon-7"]))
        (sut/run (assoc opts :_raw-args ["start" "lighthouse-watch" "beacon-9"]))
        (sut/run (assoc opts :_raw-args ["signal" "lighthouse-watch" "beacon-9" "dusk"])))
      (let [out (with-out-str
                  (should= 0 (sut/run (assoc opts :_raw-args ["list" "lighthouse-watch" "--state" "lit"]))))]
        (should (str/includes? out "beacon-9"))
        (should-not (str/includes? out "beacon-7")))))

  )
