(ns shadow.css.index
  (:require
    [shadow.css.analyzer :as ana]
    [clojure.string :as str]
    [clojure.java.io :as io])
  (:import [java.io File]))

(defn add-source [idx src]
  (let [{:keys [ns] :as contents}
        (ana/find-css-in-source src)]

    (if (not contents)
      idx
      ;; index every namespace so we can follow requires properly
      ;; without anything else having to parse everything again
      (assoc-in idx [:namespaces ns] contents))))

(defn add-file [idx ^File file]
  (let [src (slurp file)]
    (add-source idx src)))

(defn clj-file? [filename]
  ;; .clj .cljs .cljc .cljd
  (str/index-of filename ".clj"))

(defn add-path
  [idx path config]
  (let [files
        (->> (io/file path)
             (file-seq)
             (filter #(clj-file? (.getName ^File %))))]

    (reduce add-file idx files)))

(defn safe-pr-str
  "cider globally sets *print-length* for the nrepl-session which messes with pr-str when used to print cache or other files"
  [x]
  (binding [*print-length* nil
            *print-level* nil
            *print-namespace-maps* nil
            *print-meta* nil]
    (pr-str x)
    ))

(defn write-to [idx output-to]
  (spit
    (doto (io/file output-to)
      (io/make-parents))
    (safe-pr-str idx))
  idx)

(defn create []
  {:version 1
   :namespaces {}})

(time
  (-> (create)
      (add-path "src/main" {})
      (write-to "tmp/foo.edn")
      (tap>)))