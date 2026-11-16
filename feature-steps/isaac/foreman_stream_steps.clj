(ns isaac.foreman-stream-steps
  (:require
    [cheshire.core :as json]
    [clojure.string :as str]
    [gherclj.core :as g :refer [defwhen defthen helper!]]
    [isaac.http.server-steps :as server-steps])
  (:import (java.io BufferedReader InputStreamReader)
           (java.net URI)
           (java.net.http HttpClient HttpRequest HttpResponse$BodyHandlers)
           (java.util.concurrent LinkedBlockingQueue TimeUnit)))

(helper! isaac.foreman-stream-steps)

(defn- rows [table]
  (let [items (cond-> (:rows table) (seq (:headers table)) (conj (:headers table)))]
    (into {} (map (fn [[k v]] [k v]) (remove #(= ["key" "value"] %) items)))))

(defn- close-stream! []
  (when-let [stream (g/get :foreman-stream)]
    (.close stream))
  (when-let [client (g/get :foreman-client)]
    (.close client)))

(g/after-scenario close-stream!)

(defn open-stream! [path table]
  (server-steps/server-running)
  (let [headers (rows table)
        port (g/get :server-port)
        builder (HttpRequest/newBuilder (URI/create (str "http://localhost:" port path)))
        _ (doseq [[k v] headers :when (str/starts-with? k "header.")]
            (.header builder (subs k 7) v))
        client (HttpClient/newHttpClient)
        response (.send client (.build builder) (HttpResponse$BodyHandlers/ofInputStream))
        stream (.body response)
        events (LinkedBlockingQueue.)]
    (g/assoc! :http-response {:status (.statusCode response)})
    (g/assoc! :foreman-client client)
    (g/assoc! :foreman-stream stream)
    (g/assoc! :foreman-events events)
    (g/assoc! :foreman-seen (atom []))
    (when (= 200 (.statusCode response))
      (future
        (try
          (with-open [reader (BufferedReader. (InputStreamReader. stream))]
            (doseq [line (line-seq reader)
                    :when (str/starts-with? line "data: ")]
              (.put events (json/parse-string (subs line 6)))))
          (catch Exception _ nil))))))

(defn- matches? [expected event]
  (every? (fn [[k v]] (= v (get event k))) expected))

(defn- await-match! [expected]
  (let [events (g/get :foreman-events)
        deadline (+ (System/nanoTime) (* 5 1000000000))]
    (loop []
      (when (< (System/nanoTime) deadline)
        (if-let [event (.poll events 100 TimeUnit/MILLISECONDS)]
          (do (swap! (g/get :foreman-seen) conj event)
              (if (matches? expected event) event (recur)))
          (recur))))))

(defn stream-event-matches [table]
  (let [expected (rows table)]
    (g/should (or (some #(matches? expected %) @(g/get :foreman-seen))
                  (await-match! expected)))))

(defn no-stream-event-matches [table]
  (let [expected (rows table)
        events (g/get :foreman-events)
        seen (concat @(g/get :foreman-seen) (iterator-seq (.iterator events)))]
    (g/should-not (some #(matches? expected %) seen))))

(defwhen "a client opens a stream to {path:string}:" isaac.foreman-stream-steps/open-stream!)
(defthen "the stream delivers an event matching:" isaac.foreman-stream-steps/stream-event-matches)
(defthen "the stream has delivered no event matching:" isaac.foreman-stream-steps/no-stream-event-matches)
