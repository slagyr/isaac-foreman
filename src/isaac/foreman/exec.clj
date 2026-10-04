(ns isaac.foreman.exec
  "Run administrator-declared Foreman commands as argv, not through a shell."
  (:require
    [cheshire.core :as json]
    [clojure.edn :as edn]
    [clojure.string :as str]
    [isaac.foreman.machine :as machine]
    [isaac.foundation.logger :as log])
  (:import (java.io File)
           (java.util.concurrent TimeUnit)))

(defn- structured-output [text]
  (let [text (str/trim text)]
    (or (some (fn [parse]
                (try
                  (let [value (parse text)]
                    (when (or (map? value) (vector? value)) value))
                  (catch Exception _ nil)))
              [#(json/parse-string % true) edn/read-string])
        text)))

(defn run! [action spec machine-name id data]
  (let [fill #(machine/fill-prompt % machine-name id data)
        argv (mapv fill (:command spec))
        cwd (some-> (:cwd spec) fill)
        started (System/nanoTime)]
    (try
      (let [builder (ProcessBuilder. ^java.util.List argv)
            _ (when cwd (.directory builder (File. cwd)))
            process (.start builder)
            stdout (future (slurp (.getInputStream process)))
            stderr (future (slurp (.getErrorStream process)))
            finished (.waitFor process (long (or (:timeout spec) 60)) TimeUnit/SECONDS)
            _ (when-not finished (.destroyForcibly process))
            exit (if finished (.exitValue process) (do (.waitFor process) -1))
            out (str/trim @stdout)
            err (str/trim @stderr)
            elapsed (long (/ (- (System/nanoTime) started) 1000000))]
        (log/info :foreman/exec :action action :argv argv :exit exit :ms elapsed)
        (if (and finished (zero? exit))
          {:output (structured-output out)}
          {:failure {:action action :exit exit :stderr (subs err 0 (min 1024 (count err)))
                     :timeout (not finished)}}))
      (catch Exception e
        (log/error :foreman/exec :action action :argv argv :exit -1
                   :ms (long (/ (- (System/nanoTime) started) 1000000)))
        {:failure {:action action :exit -1 :stderr (or (ex-message e) "") :timeout false}}))))
