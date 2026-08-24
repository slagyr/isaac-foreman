(ns isaac.foreman.store-spec
  (:require
    [clojure.edn :as edn]
    [clojure.string :as str]
    [isaac.foreman.store :as sut]
    [isaac.fs :as fs]
    [isaac.nexus :as nexus]
    [speclj.core :refer :all]))

(describe "isaac.foreman.store"

  (with mem (fs/mem-fs))
  (with root "/isaac-state")

  (around [example]
    (nexus/-with-nested-nexus {:fs @mem}
      (fs/mkdirs @mem @root)
      (example)))

  (it "creates an instance at :initial with empty pending and history"
    (let [inst (sut/create-instance! {:fs @mem :root @root
                                      :machine "lighthouse-watch"
                                      :id      "beacon-7"
                                      :state   :dark
                                      :now     "2026-03-01T18:00:00Z"})]
      (should= :dark (:state inst))
      (should= "beacon-7" (:id inst))
      (should= [] (:pending-actions inst))
      (should= "2026-03-01T18:00:00Z" (:since inst))
      (let [edn-path (str @root "/foreman/lighthouse-watch/beacon-7.edn")
            ev-path  (str @root "/foreman/lighthouse-watch/beacon-7.events.ednl")]
        (should (fs/exists? @mem edn-path))
        (should= {:state :dark :context {} :pending-actions [] :since "2026-03-01T18:00:00Z"}
                 (dissoc (edn/read-string (fs/slurp @mem edn-path)) :id))
        (should (fs/exists? @mem ev-path)))))

  (it "refuses to create a second instance with the same id"
    (sut/create-instance! {:fs @mem :root @root :machine "lighthouse-watch"
                           :id "beacon-7" :state :dark :now "2026-03-01T18:00:00Z"})
    (should-throw Exception #"already exists"
      (sut/create-instance! {:fs @mem :root @root :machine "lighthouse-watch"
                             :id "beacon-7" :state :dark :now "2026-03-01T18:00:00Z"})))

  (it "returns nil for an unknown instance"
    (should-be-nil (sut/get-instance {:fs @mem :root @root
                                      :machine "lighthouse-watch" :id "ghost-9"})))

  (it "appends a handled event to history before returning the updated instance"
    (sut/create-instance! {:fs @mem :root @root :machine "lighthouse-watch"
                           :id "beacon-7" :state :dark :now "2026-03-01T18:00:00Z"})
    (let [inst (sut/record-transition! {:fs      @mem
                                        :root    @root
                                        :machine "lighthouse-watch"
                                        :id      "beacon-7"
                                        :from    :dark
                                        :to      :lit
                                        :event   :dusk
                                        :actions [:light-lamp]
                                        :now     "2026-03-01T18:00:00Z"})]
      (should= :lit (:state inst))
      (should= "2026-03-01T18:00:00Z" (:since inst))
      (let [ev-path (str @root "/foreman/lighthouse-watch/beacon-7.events.ednl")
            lines   (str/split-lines (fs/slurp @mem ev-path))]
        (should= 1 (count lines))
        (should= {:type :transition :from :dark :to :lit :event :dusk :actions [:light-lamp]
                  :at "2026-03-01T18:00:00Z"}
                 (edn/read-string (first lines))))))

  (it "records unhandled events without changing state"
    (sut/create-instance! {:fs @mem :root @root :machine "lighthouse-watch"
                           :id "beacon-7" :state :dark :now "2026-03-01T18:00:00Z"})
    (let [inst (sut/record-unhandled! {:fs      @mem
                                       :root    @root
                                       :machine "lighthouse-watch"
                                       :id      "beacon-7"
                                       :state   :dark
                                       :event   :earthquake
                                       :now     "2026-03-01T18:00:00Z"})]
      (should= :dark (:state inst))
      (let [ev-path (str @root "/foreman/lighthouse-watch/beacon-7.events.ednl")
            rec     (edn/read-string (fs/slurp @mem ev-path))]
        (should= :unhandled (:type rec))
        (should= :earthquake (:event rec))
        (should= :dark (:state rec)))))

  (it "lists instances, optionally filtered by current state"
    (sut/create-instance! {:fs @mem :root @root :machine "lighthouse-watch"
                           :id "beacon-7" :state :dark :now "2026-03-01T18:00:00Z"})
    (sut/create-instance! {:fs @mem :root @root :machine "lighthouse-watch"
                           :id "beacon-9" :state :dark :now "2026-03-01T18:00:00Z"})
    (sut/record-transition! {:fs @mem :root @root :machine "lighthouse-watch"
                             :id "beacon-9" :from :dark :to :lit :event :dusk
                             :actions [] :now "2026-03-01T18:00:00Z"})
    (let [all  (sut/list-instances {:fs @mem :root @root :machine "lighthouse-watch"})
          lit  (sut/list-instances {:fs @mem :root @root :machine "lighthouse-watch" :state :lit})]
      (should= #{"beacon-7" "beacon-9"} (set (map :id all)))
      (should= ["beacon-9"] (mapv :id lit))))

  )
