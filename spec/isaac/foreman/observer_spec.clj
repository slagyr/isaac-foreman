(ns isaac.foreman.observer-spec
  (:require
    [isaac.foreman.observer :as sut]
    [isaac.logger :as log]
    [speclj.core :refer :all]))

(describe "isaac.foreman.observer"

  (it "notifies registered observers of a handled transition"
    (let [seen (atom [])]
      (sut/register! :probe (fn [event] (swap! seen conj event)))
      (sut/notify! {:observers [:probe]
                    :machine   "lighthouse-watch"
                    :id        "beacon-7"
                    :from      :dark
                    :to        :lit
                    :event     :dusk})
      (should= 1 (count @seen))
      (should= :dusk (:event (first @seen)))
      (should= :lit (:to (first @seen)))
      (sut/clear!)))

  (it "does not notify observers of unhandled events"
    (let [seen (atom [])]
      (sut/register! :probe (fn [event] (swap! seen conj event)))
      (sut/notify! {:observers [:probe]
                    :machine   "lighthouse-watch"
                    :id        "beacon-7"
                    :unhandled? true
                    :state     :dark
                    :event     :earthquake})
      (should= [] @seen)
      (sut/clear!)))

  (it "built-in :log observer records the transition"
    (log/clear-entries!)
    (log/set-output! :memory)
    (sut/notify! {:observers [:log]
                  :machine   "lighthouse-watch"
                  :id        "beacon-7"
                  :from      :dark
                  :to        :lit
                  :event     :dusk})
    (let [entries (log/get-entries)]
      (should (seq entries))
      (should (some #(= :foreman/transition (:event %)) entries))))

  )
