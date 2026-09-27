(ns isaac.foreman.turn-observer-spec
  (:require
    [isaac.drive.observer :as drive]
    [isaac.foreman.turn-observer :as sut]
    [speclj.core :refer :all]))

(describe "Foreman turn observations"
  (it "reports a died turn to its machine instance"
    (let [seen (atom [])]
      (with-redefs [sut/emit! (fn [machine instance event ctx]
                                (swap! seen conj [machine instance event (:session-key ctx)]))]
        (drive/on-turn-died (sut/factory ["lighthouse-watch" "beacon-7"])
                            {:session-key "lamp-room"} :killed))
      (should= [["lighthouse-watch" "beacon-7" :turn-died "lamp-room"]] @seen))))
