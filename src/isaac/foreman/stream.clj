(ns isaac.foreman.stream
  "Follow persisted Foreman transitions so signals from other processes are visible."
  (:require
    [cheshire.core :as json]
    [isaac.foreman.store :as store]
    [isaac.foundation.fs :as fs]
    [isaac.foundation.config.loader :as loader]
    [org.httpkit.server :as httpkit]))

(defn- instances [opts instance]
  (if instance
    (if-let [inst (store/get-instance (assoc opts :id instance))] [inst] [])
    (store/list-instances opts)))

(defn- event [machine instance rec]
  (cond-> {:type "transition" :machine machine :instance instance
           :id (:id rec) :event (some-> (:event rec) name)
           :from (some-> (:from rec) name) :to (some-> (:to rec) name)
           :at (:at rec)}
    (get-in rec [:data :reason]) (assoc :reason (get-in rec [:data :reason]))))

(defn- send-event! [channel value]
  (httpkit/send! channel (str "data: " (json/generate-string value) "\n\n") false))

(defn- poll! [channel opts instance cursors]
  (reduce (fn [positions inst]
            (let [id (:id inst)
                  records (store/history (assoc opts :id id))
                  offset (get positions id 0)]
              (doseq [rec (drop offset records) :when (= :transition (:type rec))]
                (send-event! channel (event (:machine opts) id rec)))
              (assoc positions id (count records))))
          cursors (instances opts instance)))

(defn open! [request machine instance]
  (let [opts {:root (loader/root) :fs (fs/instance) :machine machine}
        active (instances opts instance)
        positions (atom (into {} (map (fn [inst] [(:id inst) (count (store/history (assoc opts :id (:id inst))))]) active)))]
    (httpkit/as-channel request
      {:on-open (fn [channel]
                  (httpkit/send! channel {:status 200 :headers {"Content-Type" "text/event-stream"
                                                                "Cache-Control" "no-cache"}
                                          :body ": connected\n\n"} false)
                  (doseq [inst active]
                    (send-event! channel {:type "state" :machine machine :instance (:id inst)
                                          :state (name (:state inst))}))
                  (let [running (atom true)]
                    (httpkit/on-close channel (fn [_] (reset! running false)))
                    (doto (Thread. (fn []
                                     (while @running
                                       (try
                                         (swap! positions #(poll! channel opts instance %))
                                         (httpkit/send! channel ": heartbeat\n\n" false)
                                         (catch Exception _ (reset! running false)))
                                       (when @running (Thread/sleep 200))))
                                   "foreman-stream")
                      (.setDaemon true)
                      (.start))))})))
