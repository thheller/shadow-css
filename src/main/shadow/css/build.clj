(ns shadow.css.build
  (:require
    [shadow.css.specs :as s]
    [shadow.css.analyzer :as ana]
    [shadow.css.compiler :as comp]
    [shadow.css.index :as index]
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [clojure.string :as str]))

;; same naming patterns tailwind uses
(def spacing-alias-groups
  ;; padding
  {"p-" [:padding]
   "px-" [:padding-left :padding-right]
   "py-" [:padding-top :padding-bottom]
   "pt-" [:padding-top]
   "pb-" [:padding-bottomn]
   "pl-" [:padding-left]
   "pr-" [:padding-right]

   ;; margin
   "m-" [:margin]
   "mx-" [:margin-left :margin-right]
   "my-" [:margin-top :margin-bottom]
   "mt-" [:margin-top]
   "mb-" [:margin-bottom]
   "ml-" [:margin-left]
   "mr-" [:margin-right]

   ;; positions
   "top-" [:top]
   "right-" [:right]
   "bottom-" [:bottom]
   "left-" [:left]

   "inset-x" [:left :right]
   "inset-y" [:top :bottom]

   ;; width
   "w-" [:width]
   "max-w-" [:max-width]

   ;; height
   "h-" [:height]
   "max-h-" [:max-height]

   ;; flex
   "basis-" [:flex-basis]

   ;; grid
   "gap-" [:gap]
   "gap-x-" [:column-gap]
   "gap-y-" [:row-gap]

   ["space-x-" "& > * + *"] [:padding-left :padding-right]
   ["space-y-" "& > * + *"] [:padding-top :padding-bottom]
   })

(defn generate-spacing-aliases [{:keys [spacing] :as svc}]
  (update svc :aliases
    (fn [aliases]
      (reduce-kv
        (fn [aliases space-num space-val]
          (reduce-kv
            (fn [aliases prefix props]
              (if (string? prefix)
                (assoc aliases (keyword (str prefix space-num)) (reduce #(assoc %1 %2 space-val) {} props))
                (let [[prefix sub-sel] prefix]
                  (assoc aliases (keyword (str prefix space-num)) [[sub-sel (reduce #(assoc %1 %2 space-val) {} props)]])
                  )))
            aliases
            spacing-alias-groups))
        aliases
        spacing))))

(defn load-default-aliases []
  (edn/read-string (slurp (io/resource "shadow/css/aliases.edn"))))

(def color-alias-groups
  {"bg-" [:background-color]
   "border-" [:border-color]
   "outline-" [:outline-color]
   "text-" [:color]})

(defn generate-color-aliases* [aliases]
  (let [colors (edn/read-string (slurp (io/resource "shadow/css/colors.edn")))]
    (reduce
      (fn [aliases [color-name suffix color]]
        (reduce-kv
          (fn [aliases alias-prefix props]
            (assoc aliases
              (keyword (str alias-prefix color-name suffix))
              (reduce #(assoc %1 %2 color) {} props)))
          aliases
          color-alias-groups))
      aliases
      (for [[name vals] colors
            [suffix color] vals]
        [name suffix color]))))

(defn generate-color-aliases [svc]
  (update svc :aliases generate-color-aliases*))

(defn load-indexes-from-classpath [index]
  (reduce
    (fn [index url]
      (let [{:keys [version namespaces] :as contents}
            (-> (slurp url)
                (edn/read-string))]
        (-> index
            (assoc-in [:sources url] contents)
            (update :namespaces merge namespaces))))
    index
    (-> (Thread/currentThread)
        (.getContextClassLoader)
        (.getResources "shadow-css-index.edn")
        (enumeration-seq))))

(defn build-css-for-chunk
  [{:keys [index-ref] :as svc} {:keys [include] :as entries}]
  ;; FIXME: should support :entries and follow them, but for that we need full namespace indexing

  (let [{:keys [namespaces] :as index} @index-ref

        namespace-matchers
        (->> include
             (map (fn [x]
                    (cond
                      (string? x)
                      (let [re (re-pattern x)]
                        #(re-find re %))

                      (not (symbol? x))
                      (throw (ex-info "invalid include pattern" {:x x}))

                      :else
                      (let [s (str x)]
                        ;; FIXME: allow more patterns that can be expressed as string?
                        ;; foo.bar.*.views?

                        (if (str/ends-with? s "*")
                          ;; foo.bar.* - prefix match
                          (let [prefix (subs s 0 (-> s count dec))]
                            (fn [ns]
                              (str/starts-with? (str ns) prefix)))

                          ;; exact match
                          (fn [ns]
                            (= x ns))
                          )))))
             (into []))

        included-namespaces
        (->> (vals namespaces)
             (filter (fn [{:keys [ns]}]
                       (some (fn [matcher] (matcher ns)) namespace-matchers)))
             (into []))

        all-rules
        (->> (for [{:keys [ns css] :as ns-info} included-namespaces
                   {:keys [line column] :as form-info} css
                   :let [css-id (s/generate-id ns line column)]]
               (-> (ana/process-form svc form-info)
                   (assoc
                     :ns ns
                     :css-id css-id
                     ;; FIXME: when adding optimization pass selector won't be based on css-id anymore
                     :sel (str "." css-id)))
               )
             (into []))

        cp-includes
        (into #{} (for [{:keys [ns-meta]} included-namespaces
                        include (:shadow.css/include ns-meta)]
                    include))

        warnings
        (vec
          (for [{:keys [warnings ns line column]} all-rules
                warning warnings]
            (assoc warning :ns ns :line line :column column)))]

    ;; TODO: merge media queries so they are only emitted once and not per rule
    ;; TODO: add optional optimization step to collapse classes
    ;; TODO: css minifier
    (merge
      {:warnings warnings
       :classpath-includes cp-includes
       :rules all-rules}
      (comp/generate-css all-rules))))

(defn generate [{:keys [preflight-src index-ref] :as svc} {:keys [output-dir chunks] :as build-config}]
  (let [output-dir (io/file output-dir)]

    ;; just to make sure output-dir exists
    (doto (io/file output-dir "foo.txt")
      (io/make-parents))

    ;; FIXME: actually support chunks, similar to CLJS with :depends-on #{:other-chunk}
    ;; so chunks don't repeat everything, for that needs to analyze chunks first
    ;; then produce output
    (reduce-kv
      (fn [results chunk-id chunk]
        (let [output-file (io/file output-dir (str (name chunk-id) ".css"))
              {:keys [css classpath-includes] :as output} (build-css-for-chunk svc chunk)
              css (str preflight-src "\n"
                       css "\n"
                       (->> classpath-includes
                            (map #(slurp (io/resource %)))
                            (str/join "\n")))]

          (spit output-file css)

          (conj results output)
          ))
      []
      chunks)))

(defn index-path [{:keys [index-ref] :as svc} path opts]
  (swap! index-ref index/add-path path opts)
  svc)

(defn index-file [{:keys [index-ref] :as svc} file]
  (swap! index-ref index/add-file file)
  svc)

(defn start [config]
  (-> {:config
       config

       :index-ref
       (-> (index/create)
           (load-indexes-from-classpath)
           (atom))

       :manifest-cache-dir
       (doto (io/file ".shadow-cljs" "css")
         (io/make-parents))

       :aliases
       (load-default-aliases)

       ;; https://tailwindcss.com/docs/customizing-spacing#default-spacing-scale
       :spacing
       {0 "0"
        0.5 "0.125rem"
        1 "0.25rem"
        1.5 "0.375rem"
        2 "0.5rem"
        2.5 "0.626rem"
        3 "0.75rem"
        3.5 "0.875rem"
        4 "1rem"
        5 "1.25rem"
        6 "1.5rem"
        7 "1.75rem"
        8 "2rem"
        9 "2.25rem"
        10 "2.5rem"
        11 "2.75rem"
        12 "3rem"
        13 "3.25rem"
        14 "3.5rem"
        15 "3.75rem"
        16 "4rem"
        17 "4.25rem"
        18 "4.5rem"
        19 "4.75rem"
        20 "5rem"
        24 "6rem"
        28 "7rem"
        32 "8rem"
        36 "9rem"
        40 "10rem"
        44 "11rem"
        48 "12rem"
        52 "13rem"
        56 "14rem"
        60 "15rem"
        64 "16rem"
        96 "24rem"}

       :preflight-src
       (slurp (io/resource "shadow/css/preflight.css"))}

      (generate-color-aliases)
      (generate-spacing-aliases)))

(defn stop [svc])