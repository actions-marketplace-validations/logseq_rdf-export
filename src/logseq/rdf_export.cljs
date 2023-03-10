(ns logseq.rdf-export
  "This ns converts a subset of a Logseq graph to an rdf file using
https://github.com/rdfjs/N3.js'

All of the above pages can be customized with query config options."
  (:require ["n3" :refer [DataFactory Writer]]
            ["fs" :as fs]
            ["path" :as path]
            [clojure.string :as string]
            [datascript.core :as d]
            [logseq.db.rules :as rules]
            [babashka.cli :as cli]
            [clojure.edn :as edn]
            [logseq.graph-parser.cli :as gp-cli]
            [logseq.rdf-export.config :as config]
            [datascript.transit :as dt]))


(defn- propertify
  [result]
  (map #(-> (:block/properties %)
            (dissoc :title)
            (assoc :block/original-name
                   (or (get-in % [:block/properties :title]) (:block/original-name %))))
       result))

(defn- page-url [page-name config]
  (str (:base-url config) (js/encodeURIComponent page-name)))

(defn- triplify
  "Turns an entity map into a coll of triples"
  [m {:keys [url-property] :as config} property-map]
  (mapcat
   (fn [prop]
     (map #(vector (page-url (:block/original-name m) config)
                   (or (url-property (get property-map prop))
                       (page-url (name prop) config))
                   %)
          (let [v (m prop)]
            ;; If a collection, they are refs/pages
            (if (coll? v) (map #(page-url % config) v) [v]))))
   (keys m)))

(defn- add-classes [db config property-map]
  (->> (d/q (:class-query config)
            db
            (vals rules/query-dsl-rules))
       (map first)
       propertify
       (mapcat #(triplify % config property-map))))

(defn- add-properties [properties config property-map]
  (->> properties
       (mapcat #(triplify % config property-map))))

(defn- add-additional-pages [db config property-map]
  (->> (d/q '[:find (pull ?b [*])
              :in $ ?names %
              :where
              [?b :block/name ?n]
              [(contains? ?names ?n)]]
            db
            (set (map string/lower-case (:additional-pages config)))
            (vals rules/query-dsl-rules))
       (map first)
       propertify
       (mapcat #(triplify % config property-map))))

(defn- add-class-instances [db config property-map]
  (->> (d/q (:class-instances-query config)
            db
            (vals rules/query-dsl-rules))
       (map first)
       propertify
       (mapcat #(triplify % config property-map))))

(defn- create-quads [_writer db config]
  (let [properties (->> (d/q (:property-query config)
                             db
                             (vals rules/query-dsl-rules))
                        (map first)
                        propertify)
        built-in-properties {:block/original-name {:url "https://schema.org/name"}
                             :alias {:url "https://schema.org/sameAs"}}
        property-map (into built-in-properties
                           (map (juxt (comp keyword :block/original-name) identity)
                                properties))]

    (concat
     (add-additional-pages db config property-map)
     (add-classes db config property-map)
     (add-properties properties config property-map)
     (add-class-instances db config property-map))))

(defn- add-quads [writer quads]
  (doseq [[q1 q2 q3]
          (map (fn [q]
                 (map #(if (and (string? %) (string/starts-with? % "http"))
                         (.namedNode DataFactory %)
                         (.literal DataFactory %))
                      q))
               quads)]
    (.addQuad writer (.quad DataFactory q1 q2 q3))))

(defn- read-config [dir config]
  (when-let [config-body (if (seq config)
                           config
                           (when (fs/existsSync (path/join dir ".rdf-export" "config.edn"))
                             (str (fs/readFileSync (path/join dir ".rdf-export" "config.edn")))))]
    (try
      (edn/read-string config-body)
      (catch :default _
        (println "Error: Failed to parse config. Make sure it is valid EDN")
        (js/process.exit 1)))))

(defn get-graph-config [dir user-config]
  (let [config (read-config dir user-config)]
    (merge-with (fn [v1 v2]
                  (if (and (map? v1) (map? v2))
                    (merge v1 v2) v2))
                config/default-config
                config)))

(def spec
  "Options spec"
  {:config {:alias :c
            :desc "Edn config map"}
   :directory {:desc "Graph directory to export"
               :alias :d
               :default "."}
   :help   {:alias :h
            :coerce :boolean
            :desc "Print help"}})

(defn- get-db
  "If cached db exists get it, otherwise parse for a fresh db"
  [graph-dir cache-dir]
  ;; cache-db from https://github.com/logseq/bb-tasks
  (let [cache-file (path/join (or cache-dir ".") ".cached-db-transit.json")]
    (if (fs/existsSync cache-file)
      (do
        (println "Reading from cached db")
        (dt/read-transit-str (fs/readFileSync cache-file)))
      (let [{:keys [conn]} (gp-cli/parse-graph graph-dir)] @conn))))

(defn write-rdf-file
  "Given a graph's dir, covert to rdf and write to given file."
  [dir file & [options]]
  (let [graph-config (get-graph-config dir (:config options))
        db (get-db dir (:cache-dir options))
        writer (Writer. (clj->js {:prefixes (:prefixes graph-config)
                                  :format (:format graph-config)}))
        quads (create-quads writer db graph-config)]
    (add-quads writer quads)
    (.end writer (fn [_err result]
                   (println "Writing" (count quads) "triples to file" file)
                   (fs/writeFileSync file result)))))

(defn -main [& args]
  (let [{:keys [directory help] :as options} (cli/parse-opts args {:spec spec})
        _ (when (or help (zero? (count args)))
            (println (str "Usage: logseq-rdf-export FILE [OPTIONS]\nOptions:\n"
                          (cli/format-opts {:spec spec})))
            (js/process.exit 1))
        ;; In CI, move up a directory since the script is run in subdirectory of
        ;; a project
        directory' (if js/process.env.CI (path/join ".." directory) directory)]
    (write-rdf-file directory' (first args) (select-keys options [:cache-dir :config]))))

#js {:main -main}
