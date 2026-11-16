(ns isaac.foreman.stream
  "Stream persisted Foreman transitions from the server's in-process bus."
  (:require
    [cheshire.core :as json]
    [isaac.foreman.bus :as bus]
    [isaac.foreman.store :as store]
    [isaac.foundation.config.loader :as loader]
    [isaac.foundation.fs :as fs]
    [isaac.foundation.logger :as log]
    [org.httpkit.server :as httpkit])
  (:import (java.util.concurrent Executors ScheduledExecutorService TimeUnit)))

(defonce ^:private heartbeats
  (Executors/newSingleThreadScheduledExecutor
    ^java.util.concurrent.ThreadFactory (reify java.util.concurrent.ThreadFactory
      (newThread [_ task] (doto (Thread. task "foreman-heartbeats") (.setDaemon true))))))

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

(defn- send! [channel text]
  (when (false? (httpkit/send! channel text false))
    (throw (ex-info "stream closed" {}))))

(defn- send-event! [channel value]
  (send! channel (str "data: " (json/generate-string value) "\n\n")))

(defn open! [request machine instance]
  (let [opts {:root (loader/root) :fs (fs/instance) :machine machine}]
    (httpkit/as-channel request
      {:on-open
       (fn [channel]
         (let [lock (Object.)
               pending (atom [])
               ready? (atom false)
               closed? (atom false)
               unsubscribe (atom nil)
               heartbeat (atom nil)
               close! (fn []
                        (when (compare-and-set! closed? false true)
                          (when-let [stop @unsubscribe] (stop))
                          (when-let [timer @heartbeat] (.cancel timer false))))]
           (try
             (let [watch (fn [message]
                           (when (and (= (:root opts) (:root message))
                                      (= machine (:machine message))
                                      (or (nil? instance) (= instance (:instance message))))
                             (locking lock
                               (if @ready?
                                 (when (= :transition (get-in message [:record :type]))
                                   (send-event! channel (event machine (:instance message) (:record message))))
                                 (swap! pending conj message)))))]
               (reset! unsubscribe (bus/subscribe! watch))
               (httpkit/on-close channel (fn [_] (close!)))
               (send! channel {:status 200 :headers {"Content-Type" "text/event-stream"
                                                     "Cache-Control" "no-cache"}
                               :body ": connected\n\n"})
               (doseq [inst (instances opts instance)]
                 (send-event! channel {:type "state" :machine machine :instance (:id inst)
                                       :state (name (:state inst))}))
               (locking lock
                 (doseq [message @pending]
                   (when (= :transition (get-in message [:record :type]))
                     (send-event! channel (event machine (:instance message) (:record message)))))
                 (reset! pending [])
                 (reset! ready? true))
               (when-not @closed?
                 (reset! heartbeat
                         (.scheduleAtFixedRate ^ScheduledExecutorService heartbeats
                           ^Runnable (fn []
                                       (try (send! channel ": heartbeat\n\n")
                                            (catch Exception e
                                              (log/error :foreman.stream/error :machine machine :cause (str e))
                                              (close!))))
                           15 15 TimeUnit/SECONDS)))
               (when @closed? (close!)))
             (catch Exception e
               (log/error :foreman.stream/error :machine machine :cause (str e))
               (close!)))))})))
