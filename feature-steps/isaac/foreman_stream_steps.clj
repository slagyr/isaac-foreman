(ns isaac.foreman-stream-steps
  (:require
    [cheshire.core :as json]
    [clojure.string :as str]
    [gherclj.core :as g :refer [defwhen defthen helper!]]
    [isaac.http.server-steps :as server-steps]
    [isaac.foundation.logger :as log])
  (:import (java.io BufferedReader InputStreamReader)
           (java.net URI)
           (java.net.http HttpClient HttpRequest HttpResponse$BodyHandlers)
           (java.util.concurrent LinkedBlockingQueue TimeUnit)))

(helper! isaac.foreman-stream-steps)

(defn- rows [table]
  (let [items (cond-> (:rows table) (seq (:headers table)) (conj (:headers table)))]
    (into {} (map (fn [[k v]] [k v]) (remove #(= ["key" "value"] %) items)))))

(defn- close-stream! [slot]
  (when-let [stream (g/get (keyword (str slot "-stream")))] (.close stream))
  (when-let [client (g/get (keyword (str slot "-client")))] (.close client)))

(g/after-scenario (fn [] (close-stream! :foreman) (close-stream! :foreman-second)))

(defn- open! [slot path table]
  (when-not (g/get :server-port) (server-steps/server-running))
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
    (g/assoc! (keyword (str slot "-client")) client)
    (g/assoc! (keyword (str slot "-stream")) stream)
    (g/assoc! (keyword (str slot "-events")) events)
    (g/assoc! (keyword (str slot "-seen")) (atom []))
    (when (= 200 (.statusCode response))
      (future
        (try
          (with-open [reader (BufferedReader. (InputStreamReader. stream))]
            (doseq [line (line-seq reader)
                    :when (str/starts-with? line "data: ")]
              (.put events (json/parse-string (subs line 6)))))
          (catch Exception _ nil))))))

(defn open-stream! [path table] (open! :foreman path table))
(defn open-second-stream! [path table] (open! :foreman-second path table))
(defn client-closes-stream! [] (close-stream! :foreman))

(defn- matches? [expected event]
  (every? (fn [[k v]] (= v (get event k))) expected))

(defn- await-match! [slot expected]
  (let [events (g/get (keyword (str slot "-events")))
        deadline (+ (System/nanoTime) (* 5 1000000000))]
    (loop []
      (when (< (System/nanoTime) deadline)
        (if-let [event (.poll events 100 TimeUnit/MILLISECONDS)]
          (do (swap! (g/get (keyword (str slot "-seen"))) conj event)
              (if (matches? expected event) event (recur)))
          (recur))))))

(defn- matched-event! [slot expected]
  (g/should (or (some #(matches? expected %) @(g/get (keyword (str slot "-seen"))))
                (await-match! slot expected))))

(defn stream-event-matches [table] (matched-event! :foreman (rows table)))
(defn second-stream-event-matches [table] (matched-event! :foreman-second (rows table)))

(defn- seen-events [slot]
  (concat @(g/get (keyword (str slot "-seen")))
          (iterator-seq (.iterator (g/get (keyword (str slot "-events")))))))

(defn no-stream-event-matches [table]
  (g/should-not (some #(matches? (rows table) %) (seen-events :foreman))))

(defn exactly-events-matching [slot n table]
  (g/should= n (count (filter #(matches? (rows table) %) (seen-events slot)))))

(defn stream-count [n table] (exactly-events-matching :foreman n table))
(defn second-stream-count [n table] (exactly-events-matching :foreman-second n table))

(defwhen "a client opens a stream to {path:string}:" isaac.foreman-stream-steps/open-stream!)
(defwhen "a second client opens a stream to {path:string}:" isaac.foreman-stream-steps/open-second-stream!)
(defwhen "the client closes the stream" isaac.foreman-stream-steps/client-closes-stream!)
(defthen "the stream delivers an event matching:" isaac.foreman-stream-steps/stream-event-matches)
(defthen "the second stream delivers an event matching:" isaac.foreman-stream-steps/second-stream-event-matches)
(defthen "the stream has delivered no event matching:" isaac.foreman-stream-steps/no-stream-event-matches)
(defthen "the stream has delivered exactly {n:int} event matching:" isaac.foreman-stream-steps/stream-count)
(defthen "the second stream has delivered exactly {n:int} event matching:" isaac.foreman-stream-steps/second-stream-count)
