(ns shadow.css.build
  (:require
    [shadow.css.specs :as s]
    [shadow.css.analyzer :as ana]
    [clojure.set :as set]
    [clojure.string :as str]
    #?@(:clj
        [[clojure.edn :as edn]
         [clojure.java.io :as io]]
        :cljs
        [[cljs.reader :as edn]]))
  #?(:clj
     (:import [java.io File Writer StringWriter])
     :cljs
     (:import [goog.string StringBuffer])))

(defn generate-spacing-aliases [{:keys [alias-groups spacing] :as build-state}]
  (update build-state :aliases
    (fn [aliases]
      (reduce-kv
        (fn [aliases space-num space-val]
          (reduce-kv
            (fn [aliases prefix props]
              (if (string? prefix)
                (assoc aliases (keyword (str prefix space-num)) (reduce #(assoc %1 %2 (if (= \- (first prefix))
                                                                                        (str "-" space-val)
                                                                                        space-val)) {} props))
                (let [[prefix sub-sel] prefix]
                  (assoc aliases (keyword (str prefix space-num)) [[sub-sel (reduce #(assoc %1 %2 space-val) {} props)]])
                  )))
            aliases
            (:spacing alias-groups)))
        aliases
        spacing))))

(defn generate-color-aliases* [aliases alias-groups colors]
  (reduce
    (fn [aliases [color-name suffix color]]
      (reduce-kv
        (fn [aliases alias-prefix props]
          (assoc aliases
            (keyword (str alias-prefix color-name suffix))
            (cond
              (keyword? props)
              {props color}

              (fn? props)
              (props color)

              :else
              nil)))
        aliases
        (:color alias-groups)))
    aliases
    (for [[name vals] colors
          [suffix color] vals]
      [name suffix color])))

(defn generate-color-aliases [{:keys [alias-groups colors] :as build-state}]
  (update build-state :aliases generate-color-aliases* alias-groups colors))

;; helper methods for eventual data collection for source mapping
(defn emits
  #?(:clj
     ([^Writer w ^String s]
      (.write w s))
     :cljs
     ([w s]
      (.append w s)))
  ([w s & more]
   (emits w s)
   (doseq [s more]
     (emits w s))))

(defn emitln
  #?(:clj
     ([^Writer w]
      (.write w "\n"))
     :cljs
     ([sb]
      (.append sb "\n")))
  ([w & args]
   (doseq [s args]
     (emits w s))
   (emitln w)))

(defn emit-rule [w sel rules]
  (doseq [[group-sel group-rules] rules]
    (emitln w (str/replace group-sel #"&" sel) " {")
    (doseq [prop (sort (keys group-rules))]
      (emitln w "  " (name prop) ": " (get group-rules prop) ";"))
    (emitln w "}")))

(defn emit-def [w {:keys [sel rules at-rules ns line column rules] :as def}]
  ;; (emitln w (str "/* " ns " " line ":" column " */"))

  (emit-rule w sel rules)

  (doseq [[media-query rules] at-rules]
    (emitln w media-query "{")
    (emit-rule w sel rules)
    (emitln w "}")))

(defn build-css-for-chunk [build-state chunk-id]
  (update-in build-state [:chunks chunk-id]
    (fn [{:keys [base rules classpath-includes] :as chunk}]
      (assoc chunk
        :css
        (let [sw #?(:clj (StringWriter.) :cljs (StringBuffer.))]
          (when base
            (emitln sw (:preflight-src build-state)))

          #?@(:clj
              [(doseq [inc classpath-includes]
                 (emitln sw (slurp (io/resource inc))))])

          (doseq [def rules]
            (emit-def sw def))
          (.toString sw))))))

(defn collect-namespaces-for-chunk
  [{:keys [include entries] :as chunk} {:keys [namespaces] :as build-state}]
  (let [namespace-matchers
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


        {entry-namespaces :namespaces}
        (reduce
          (fn step-fn [{:keys [visited] :as m} ns]
            (cond
              (contains? visited ns)
              m

              ;; npm support later
              (string? ns)
              m

              :else
              (let [ns-info (get namespaces ns)]
                (-> m
                    (update :namespaces conj ns)
                    (update :visited conj ns)
                    (ana/reduce-> step-fn (:requires ns-info))))))

          {:visited #{}
           :namespaces #{}}
          entries)

        included-namespaces
        (->> (keys namespaces)
             (filter (fn [ns]
                       (or (contains? entry-namespaces ns)
                           (some (fn [matcher] (matcher ns)) namespace-matchers))))
             (into []))]

    (assoc chunk :namespaces included-namespaces)))

(defn build-css-for-chunks
  [{:keys [namespaces] :as build-state}]
  (reduce-kv
    (fn [build-state chunk-id chunk]
      (let [all-rules
            (->> (for [ns (:chunk-namespaces chunk)
                       :let [{:keys [ns css] :as ns-info} (get namespaces ns)]
                       {:keys [line column] :as form-info} css
                       :let [css-id (s/generate-id ns line column)]]
                   (-> (ana/process-form build-state form-info)
                       (assoc
                         :ns ns
                         :css-id css-id
                         ;; FIXME: when adding optimization pass selector won't be based on css-id anymore
                         :sel (str "." css-id))))
                 (into []))

            cp-includes
            (into #{} (for [ns (:chunk-namespaces chunk)
                            :let [{:keys [ns-meta]} (get namespaces ns)]
                            include (:shadow.css/include ns-meta)]
                        include))

            warnings
            (vec
              (for [{:keys [warnings ns line column]} all-rules
                    warning warnings]
                (assoc warning :ns ns :line line :column column)))]

        (-> build-state
            (update-in [:chunks chunk-id] assoc
              :warnings warnings
              :classpath-includes cp-includes
              :rules all-rules)
            (build-css-for-chunk chunk-id))))

    build-state
    (:chunks build-state)))

(defn trim-chunks [build-state]
  (update build-state :chunks
    (fn [chunks]
      (reduce-kv
        (fn [chunks chunk-id {:keys [depends-on namespaces] :as chunk}]
          (if-not (seq depends-on)
            (assoc-in chunks [chunk-id :chunk-namespaces] (set namespaces))
            (let [provided-by-deps
                  (reduce
                    (fn step-fn [ns-set module-id]
                      (let [{:keys [namespaces] :as other} (get chunks module-id)]
                        (-> ns-set
                            (set/union (set namespaces))
                            (ana/reduce-> step-fn (:depends-on other)))))
                    #{}
                    depends-on)]
              (assoc-in chunks [chunk-id :chunk-namespaces] (set/difference (set namespaces) provided-by-deps))
              )))
        chunks
        chunks))))

(defn generate [build-state chunks]
  ;; FIXME: actually support chunks, similar to CLJS with :depends-on #{:other-chunk}
  ;; so chunks don't repeat everything, for that needs to analyze chunks first
  ;; then produce output
  (-> build-state
      (assoc :chunks {})
      (ana/reduce-kv->
        (fn [build-state chunk-id chunk]
          (let [chunk
                (-> chunk
                    (assoc :chunk-id chunk-id)
                    (cond->
                      (not (contains? chunk :depends-on))
                      (assoc :base true))
                    (collect-namespaces-for-chunk build-state))]

            (assoc-in build-state [:chunks chunk-id] chunk)))
        chunks)
      (trim-chunks)
      (build-css-for-chunks)))

;; simplistic regexp based css minifier
;; it'll destroy some stuff for sure
;; but for now it seems to be ok and doesn't require parsing css
(defn minify-chunk [chunk]
  (update chunk :css
    (fn [css]
      (-> css
          ;; collapse multiple whitespace to one first
          (str/replace #"\s+" " ")
          ;; remove comments
          (str/replace #"\/\*(.*?)\*\/" "")
          ;; remove a few more whitespace
          (str/replace #"\s\{\s" "{")
          (str/replace #";\s+\}\s*" "}")
          (str/replace #";\s+" ";")
          (str/replace #":\s+" ":")
          (str/replace #"\s*,\s*" ",")
          ))))

(defn minify [build-state]
  (update build-state :chunks update-vals minify-chunk))

(defn index-source [build-state src]
  (let [{:keys [ns] :as contents}
        (ana/find-css-in-source src)]

    (if (not contents)
      build-state
      ;; index every namespace so we can follow requires properly
      ;; without anything else having to parse everything again
      ;; even though :css might be empty
      (assoc-in build-state [:namespaces ns] contents))))

(defn init []
  {:namespaces
   {}

   :alias-groups
   ;; same naming patterns tailwind uses
   {:color
    {"bg-" :background-color
     "border-" :border-color
     "outline-" :outline-color
     "text-" :color
     "divide-"
     (fn [color]
       [["& > * + *" {:border-color color}]])}

    :spacing
    ;; padding
    {"p-" [:padding]
     "px-" [:padding-left :padding-right]
     "py-" [:padding-top :padding-bottom]
     "pt-" [:padding-top]
     "pb-" [:padding-bottom]
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
     "-top-" [:top]
     "-right-" [:right]
     "-bottom-" [:bottom]
     "-left-" [:left]

     "inset-x-" [:left :right]
     "inset-y-" [:top :bottom]
     "inset-" [:top :right :bottom :left]
     "-inset-x-" [:left :right]
     "-inset-y-" [:top :bottom]
     "-inset-" [:top :right :bottom :left]

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
     ["space-y-" "& > * + *"] [:padding-top :padding-bottom]}}

   :aliases
   {}

   ;; https://tailwindcss.com/docs/customizing-spacing#default-spacing-scale
   :spacing
   {0 "0"
    0.5 "0.125rem"
    1 "0.25rem"
    1.5 "0.375rem"
    2 "0.5rem"
    2.5 "0.625rem"
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
    96 "24rem"}})

;; IO stuff not available in CLJS environments

#?(:clj
   (do (defn clj-file? [filename]
         ;; .clj .cljs .cljc .cljd
         (str/index-of filename ".clj"))

       (defn index-file [build-state ^File file]
         (let [src (slurp file)]
           (index-source build-state src)))

       (defn index-path
         [build-state ^File root config]
         (let [files
               (->> root
                    (file-seq)
                    (filter #(clj-file? (.getName ^File %))))]

           ;; FIXME: reducers/parallel?
           ;; takes ~80ms for entire shadow-cljs codebase which is fine
           ;; but also doesn't contain many sources with css, could be slow on bigger frontend projects
           ;; this can easily spread work in threads, just needs to merge namespaces after
           (reduce index-file build-state files)))

       (defn safe-pr-str
         "cider globally sets *print-length* for the nrepl-session which messes with pr-str when used to print cache or other files"
         [x]
         (binding [*print-length* nil
                   *print-level* nil
                   *print-namespace-maps* nil
                   *print-meta* nil]
           (pr-str x)))

       (defn write-index-to [{:keys [namespaces] :as build-state} ^File output-to]
         (io/make-parents output-to)
         (spit output-to (safe-pr-str {:version 1 :namespaces namespaces}))
         build-state)

       (defn write-outputs-to [build-state ^File output-dir]
         (reduce-kv
           (fn [_ chunk-id {:keys [css] :as chunk}]
             (let [output-file (io/file output-dir (str (name chunk-id) ".css"))]
               (io/make-parents output-file)
               (spit output-file css)))
           nil
           (:chunks build-state))

         build-state)

       (defn load-indexes-from-classpath [build-state]
         (reduce
           (fn [build-state url]
             (let [{:keys [version namespaces] :as contents}
                   (-> (slurp url)
                       (edn/read-string))]

               ;; FIXME: validate version?

               (-> build-state
                   (assoc-in [:sources url] contents)
                   (update :namespaces merge namespaces))))
           build-state
           (-> (Thread/currentThread)
               (.getContextClassLoader)
               (.getResources "shadow-css-index.edn")
               (enumeration-seq))))

       (defn merge-left [left right]
         (merge right left))

       (defn load-default-aliases-from-classpath [build-state]
         (update build-state :aliases merge-left (edn/read-string (slurp (io/resource "shadow/css/aliases.edn")))))

       (defn load-colors-from-classpath [build-state]
         (update build-state :colors merge-left (edn/read-string (slurp (io/resource "shadow/css/colors.edn")))))

       (defn load-preflight-from-classpath [build-state]
         (assoc build-state
           :preflight-src
           (slurp (io/resource "shadow/css/preflight.css"))))

       (defn start
         ([]
          (start (init)))
         ([build-state]
          (-> build-state
              (load-preflight-from-classpath)
              (load-default-aliases-from-classpath)
              (load-colors-from-classpath)
              (load-indexes-from-classpath)
              (generate-color-aliases)
              (generate-spacing-aliases))))))