(ns isaac.foreman.http
  (:require
    [cheshire.core :as json]
    [clojure.edn :as edn]
    [clojure.string :as str]
    [isaac.foundation.config.loader :as loader]
    [isaac.foreman.core :as core]
    [isaac.foundation.fs :as fs]
    [isaac.foreman.store :as store]))

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
            receipt (core/signal! {:root (loader/root) :fs (fs/instance)
                                   :machine (:machine payload) :id (:instance payload)
                                   :event (keyword (:event payload)) :data (:data payload)
                                   :event-id (:id payload) :source :http})]
        (response format 202 receipt))
      (catch clojure.lang.ExceptionInfo e
        (response format (cond
                           (:foreman/refused (ex-data e)) 409
                           (re-find #"unknown (machine|instance)" (ex-message e)) 404
                           :else 400)
                  {:error (ex-message e)}))
      (catch Exception e
        (response format 400 {:error (ex-message e)})))))

(defn data-handler [request]
  (let [format (format-of request)]
    (try
      (let [{:keys [machine id]} (:route-params request)
            body (:body request)
            text (if (string? body) body (slurp body))
            payload (if (= :edn format) (edn/read-string text) (json/parse-string text true))
            _ (when-not (map? payload) (throw (ex-info "data must be a map" {})))
            data (core/data! {:root (loader/root) :fs (fs/instance)
                              :machine machine :id id :set payload})]
        (response format 200 data))
      (catch clojure.lang.ExceptionInfo e
        (response format (if (re-find #"unknown instance" (ex-message e)) 404 400)
                  {:error (ex-message e)}))
      (catch Exception e
        (response format 400 {:error (ex-message e)})))))

(defn- payload [request format]
  (let [body (:body request)
        text (if (string? body) body (slurp body))]
    (if (= :edn format) (edn/read-string text) (json/parse-string text true))))

(defn- opts [machine id]
  {:root (loader/root) :fs (fs/instance) :machine machine :id id})

(defn- error-response [format e]
  (response format (cond
                     (re-find #"unknown (machine|instance)" (ex-message e)) 404
                     (re-find #"already exists" (ex-message e)) 409
                     :else 400)
            {:error (ex-message e)}))

(defn start-handler [request]
  (let [format (format-of request)]
    (try
      (let [{:keys [machine instance data event]} (payload request format)
            options (assoc (opts machine instance) :data data)
            inst (binding [*out* (java.io.StringWriter.)] (core/start! options))
            inst (if event
                   (do (binding [*out* (java.io.StringWriter.)]
                         (core/signal! (assoc options :event (keyword event) :source :http)))
                       (store/get-instance options))
                   inst)]
        (response format 201 inst))
      (catch Exception e (error-response format e)))))

(defn instance-handler [request]
  (let [format (format-of request)
        {:keys [machine id]} (:route-params request)
        options (opts machine id)]
    (try
      (if-let [inst (store/get-instance options)]
        (response format 200
                  (assoc (select-keys inst [:id :state :since :data])
                         :pending (:pending-actions inst)
                         :history (mapv #(select-keys % [:event :from :to :source])
                                        (filter (comp #{:transition} :type) (store/history options)))))
        (response format 404 {:error (str "unknown instance: " id)}))
      (catch Exception e (error-response format e)))))

(defn list-handler [request]
  (let [format (format-of request)
        {:keys [machine]} (:route-params request)]
    (try
      (response format 200 {:instances (mapv #(select-keys % [:id :state :since])
                                            (store/list-instances (opts machine nil)))})
      (catch Exception e (error-response format e)))))
