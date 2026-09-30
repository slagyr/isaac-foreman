(ns isaac.foreman.handbook-chapter-spec
  "Lint for isaac-foreman's own handbook chapter (isaac-7nmy): every backtick
   `config:<path>` reference must resolve against the composed config schema,
   and every `isaac <command>` invocation must name a registered top-level
   CLI command. See the convention comment at the top of the chapter file
   itself (isaac.foundation.handbook.md carries the canonical copy of the
   rule this mirrors)."
  (:require
    [clojure.java.io :as io]
    [clojure.string :as str]
    [isaac.foundation.config.schema-compose :as schema-compose]
    [isaac.foundation.config.schema.resolve :as schema-resolve]
    [isaac.foundation.fs :as fs]
    [isaac.foundation.module.discovery :as discovery]
    [isaac.foundation.nexus :as nexus]
    [speclj.core :refer :all]))

(def ^:private chapter-resource "isaac/foreman/handbook.md")

(defn- chapter-text []
  (some-> (io/resource chapter-resource) slurp))

(defn- config-refs
  "Backtick `config:<path>` references in `text`, skipping `<placeholder>`
   shapes (any reference whose path still contains an angle bracket)."
  [text]
  (->> (re-seq #"`config:([^`]+)`" text)
       (map second)
       (remove #(str/includes? % "<"))
       distinct))

(defn- cli-commands-mentioned
  "The word immediately following `isaac ` wherever it appears — inline
   code, fenced examples, or plain prose — for every top-level `isaac
   <command>` invocation in `text`."
  [text]
  (->> (re-seq #"isaac\s+([a-zA-Z][a-zA-Z0-9_-]*)" text)
       (map second)
       distinct))

(defn- known-cli-commands
  "Top-level command names contributed to the :isaac/cli berth by every
   module in `index` — read directly off each module's raw manifest rather
   than through isaac.foundation.module.berths, whose report helpers vary across
   pinned foundation shas (following isaac-imessage/isaac-agent's
   handbook-chapter-lint pattern)."
  [index]
  (->> (vals index)
       (mapcat (fn [entry] (keys (get-in entry [:manifest :isaac/cli]))))
       (map name)
       set))

(describe "isaac-foreman handbook chapter (isaac-7nmy)"

  (around [example] (nexus/-with-nexus {:fs (fs/real-fs)} (example)))

  (it "manifest declares the handbook resource"
    (let [manifest (some-> (io/resource "isaac-manifest.edn") slurp clojure.edn/read-string)]
      (should= chapter-resource (:handbook manifest))))

  (it "ships at the manifest's declared classpath resource"
    (should-not-be-nil (chapter-text)))

  (it "every `config:<path>` reference resolves against the composed config schema"
    (let [text        (chapter-text)
          root-schema (schema-compose/effective-root-schema (discovery/builtin-index))
          unresolved  (remove #(schema-resolve/schema-for-data-path root-schema %)
                              (config-refs text))]
      (should= [] unresolved)))

  (it "every `isaac <command>` invocation names a registered top-level CLI command"
    (let [text    (chapter-text)
          known   (known-cli-commands (discovery/builtin-index))
          unknown (remove known (cli-commands-mentioned text))]
      (should= [] unknown))))
