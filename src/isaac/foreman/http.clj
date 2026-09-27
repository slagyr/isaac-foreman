(ns isaac.foreman.http
  (:require
    [cheshire.core :as json]
    [clojure.edn :as edn]
    [clojure.string :as str]
    [isaac.config.root :as root]
    [isaac.foreman.core :as core]
    [isaac.fs :as fs]))

(defn- format-of [request]
  (if (str/includes? (or (get-in request [:headers "content-type"])
                         (get-in request [:headers "Content-Type"]) "") "application/edn") :edn :json))

(defn- response [format status value]
  {:status status
   :headers {"Content-Type" (if (= :edn format) "application/edn" "application/json")}
   :body (if (= :edn format) (pr-str value) (json/generate-string value))})

(defn handler [request]
  (let [format (format-of request)]
    (try
      (let [body (:body request)
            text (if (string? body) body (slurp body))
            payload (if (= :edn format) (edn/read-string text) (json/parse-string text true))
            receipt (core/signal! {:root (root/current-root) :fs (fs/instance)
                                   :machine (:machine payload) :id (:instance payload)
                                   :event (keyword (:event payload)) :data (:data payload)
                                   :event-id (:id payload) :source :http})]
        (response format 202 receipt))
      (catch clojure.lang.ExceptionInfo e
        (response format (if (re-find #"unknown (machine|instance)" (ex-message e)) 404 400)
                  {:error (ex-message e)}))
      (catch Exception e
        (response format 400 {:error (ex-message e)})))))
