(ns isaac.foreman.turn-observer-spec
  (:require
    [isaac.agent.drive.observer :as drive]
    [isaac.foreman.turn-observer :as sut]
    [isaac.foundation.logger :as log]
    [speclj.core :refer :all]))

(describe "Foreman turn observations"
  (it "reports a died turn to its machine instance"
    (let [seen (atom [])]
      (with-redefs [sut/emit! (fn [machine instance event ctx]
                                (swap! seen conj [machine instance event (:session-key ctx)]))]
        (drive/on-turn-died (sut/factory ["lighthouse-watch" "beacon-7"])
                            {:session-key "lamp-room"} :killed))
      (should= [["lighthouse-watch" "beacon-7" :foreman/turn-died "lamp-room"]] @seen)))

  (it "reports only the signal for a successful signaling turn"
    (let [seen (atom [])
          observer (sut/factory ["lighthouse-watch" "beacon-7"])
          ctx {:session-key "lamp-room" :request-id "turn-42"}]
      (with-redefs [sut/emit! (fn [_ _ event _] (swap! seen conj event))]
        (drive/on-turn-started observer ctx)
        (sut/record-signal! "lighthouse-watch" "beacon-7" "turn-42")
        (drive/on-turn-ended observer ctx :ok))
      (should= [:foreman/turn-started] @seen)))

  (it "signals the last-line event and its reason rather than turn-ended"
    (let [calls (atom [])
          observer (sut/factory ["lighthouse-watch" "beacon-7" {:mode :event :valid-events [:lit :spilled] :action :tend-lamp}])
          ctx {:session-key "lamp-room" :request-id "turn-42" :content "Wick trimmed.\nEVENT: spilled the oil is gone  \n"}]
      (with-redefs [sut/emit! (fn [_ _ event context] (swap! calls conj [event (:reason context) (:source context)]))
                    log/log* (fn [& _])
                    sut/store-output! (fn [& _])]
        (drive/on-turn-ended observer ctx :ok))
      (should= [[:spilled "the oil is gone" :reply]] @calls)))

  (it "ignores an invalid last-line event and reports a quiet turn"
    (let [events (atom [])
          observer (sut/factory ["lighthouse-watch" "beacon-7" {:mode :event :valid-events [:lit] :action :tend-lamp}])]
      (with-redefs [sut/emit! (fn [_ _ event _] (swap! events conj event))
                    log/log* (fn [& _])
                    sut/store-output! (fn [& _])]
        (drive/on-turn-ended observer {:request-id "turn-42" :content "event: dawn"} :ok))
      (should= [:foreman/turn-ended] @events)))

  (it "stores a reply before applying the unsignaled turn's outcome"
    (let [calls (atom [])
          observer (sut/factory ["lighthouse-watch" "beacon-7" :summary])
          ctx {:session-key "lamp-room" :request-id "turn-42" :content "The oil is low."}]
      (with-redefs [sut/store-output! (fn [_ _ key content] (swap! calls conj [:data key content]))
                    sut/emit! (fn [_ _ event _] (swap! calls conj [:event event]))]
        (drive/on-turn-ended observer ctx :ok))
      (should= [[:data :summary "The oil is low."] [:event :foreman/turn-ended]] @calls)))
  )

(describe "Foreman turn failures"
  (it "reports failure even after a signal"
    (let [seen (atom [])
          observer (sut/factory ["lighthouse-watch" "beacon-7"])
          ctx {:session-key "lamp-room" :request-id "turn-43"}]
      (with-redefs [sut/emit! (fn [_ _ event _] (swap! seen conj event))]
        (sut/record-signal! "lighthouse-watch" "beacon-7" "turn-43")
        (drive/on-turn-ended observer ctx {:kind :error}))
      (should= [:foreman/turn-failed] @seen)))
  )
