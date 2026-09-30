(ns isaac.foreman.tool
  (:require
    [isaac.foreman.core :as core]
    [isaac.foundation.fs :as fs]
    [isaac.agent.session.store.spi :as session-store]
    [isaac.agent.tool.fs-bounds :as bounds]))

(defn signal-tool [args]
  (let [args (bounds/string-key-map args)
        session (get args "session_key")
        store (bounds/session-store args)
        crew (or (get args "caller_crew")
                 (some-> (session-store/get-session store session) :crew))]
    (try
      (let [out (with-out-str
                  (core/signal! {:root (bounds/root args) :fs (bounds/filesystem args)
                                 :machine (get args "machine") :id (get args "instance")
                                 :event (keyword (get args "event")) :data (get args "data")
                                 :event-id (get args "id") :source :tool
                                 :crew crew :session session}))]
        {:result out})
      (catch Exception e
        {:isError true :error (ex-message e)}))))

(defn foreman-signal-tool-factory [_]
  {:builtin? true
   :description "Signal an existing Foreman machine instance."
   :parameters {:type "object"
                :properties {"machine" {:type "string"} "instance" {:type "string"}
                             "event" {:type "string"} "data" {:type "object"}
                             "id" {:type "string"}}
                :required ["machine" "instance" "event"]}
   :handler #'signal-tool})
