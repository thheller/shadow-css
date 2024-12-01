(ns shadow.css.analyzer
  (:require
    [clojure.string :as str]
    [clojure.tools.reader :as reader]
    [clojure.tools.reader.reader-types :as reader-types]
    ))

(defn reduce-> [init rfn coll]
  (reduce rfn init coll))

(defn reduce-kv-> [init rfn coll]
  (reduce-kv rfn init coll))

(defn lookup-alias [svc alias-kw]
  (get-in svc [:aliases alias-kw]))

(def plain-numeric-props
  #{:flex :order :flex-shrink :flex-grow :z-index :opacity})

(defn convert-num-val [index prop num]
  (if (contains? plain-numeric-props prop)
    (str num)
    (or (get-in index [:svc :spacing num])
        (throw
          (ex-info
            (str "invalid numeric value for prop " prop)
            {:prop prop :num num})))))

(defn add-warning [svc form warning-type warning-vals]
  (update form :warnings conj (assoc warning-vals :warning-type warning-type)))

(declare add-part)

(defn add-alias [svc form alias-kw]
  (let [alias-val (lookup-alias svc alias-kw)]
    (cond
      (not alias-val)
      (add-warning svc form ::missing-alias {:alias alias-kw})

      (map? alias-val)
      (add-part svc form alias-val)

      (vector? alias-val)
      (reduce #(add-part svc %1 %2) form alias-val)

      :else
      (add-warning svc form ::invalid-alias-replacement {:alias alias-kw :val alias-val})
      )))

(defn add-map [svc form defs]
  (reduce-kv
    (fn [form prop val]
      (let [[form val]
            (cond
              ;; {:thing "val"}
              (string? val)
              [form val]

              ;; {:thing 4}
              (number? val)
              [form (convert-num-val svc prop val)]

              ;; {:thing :alias}
              (keyword? val)
              (let [alias-value (lookup-alias svc val)]
                (cond
                  (nil? alias-value)
                  [(add-warning svc form ::missing-alias {:alias val})
                   nil]

                  (and (map? alias-value) (contains? alias-value prop))
                  [form (get alias-value prop)]

                  (string? alias-value)
                  [form alias-value]

                  (number? alias-value)
                  [form (convert-num-val form prop alias-value)]

                  :else
                  [(add-warning svc form ::invalid-map-val {:prop prop :val val})
                   nil]
                  )))]

        (assoc-in form [:rules (:sel form) prop] val)))

    form
    defs))

(defn make-sub-rule [{:keys [stack rules] :as form}]
  (update form :sub-rules assoc stack rules))

(defn add-group* [svc form group-sel group-parts]
  (cond
    (not (string? group-sel))
    (add-warning svc form ::invalid-group-sel {:sel group-sel})

    ;; at block rules such as @media or @starting-style
    (str/starts-with? group-sel "@")
    (let [{:keys [rules block]} form

          ;; FIXME: add back support for combining media queries?
          ;; I generalized this so @starting-style works but removed @media "and" combining, so this may now nest media queries
          ;; this is fine, but looks a bit prettier when combined. don't think its a common occurence to start, so no bothering for now

          ;; FIXME: @starting-style can be nested inside the main selector, maybe that should be its own special case?

          ;; @starting-style {
          ;;   .foo { ... }
          ;; }

          ;; vs

          ;; .foo {
          ;;   @starting-style {
          ;;     ...
          ;;   }}

          new-block
          (conj block group-sel)]

      (-> form
          (assoc :rules {} :block new-block)
          (reduce-> #(add-part svc %1 %2) group-parts)
          ((fn [{:keys [rules] :as form}]
             (assoc-in form [:at-rules new-block] rules)))
          (assoc :rules rules :block block)))

    (str/index-of group-sel "&")
    (let [{:keys [rules sel]} form]

      (if (not= sel "&")
        (throw (ex-info "tbd, combining &" {:sel sel :group-sel group-sel}))
        (-> form
            (assoc :sel group-sel)
            (reduce-> #(add-part svc %1 %2) group-parts)
            (assoc :sel sel))))

    :else
    (add-warning svc form ::invalid-group-sel {:sel group-sel})))

(defn add-group [svc form [sel & parts]]
  (if (keyword? sel)
    (if-some [alias-value (lookup-alias svc sel)]
      (add-group* svc form alias-value parts)
      (add-warning svc form ::group-sel-alias-not-found {:alias sel}))
    (add-group* svc form sel parts)))

(defn add-part [svc form part]
  (cond
    (string? part) ;; "other-class", passthrough, ignored here, handled in macro
    form

    (keyword? part) ;; :px-4 alias
    (add-alias svc form part)

    (map? part) ;; {:padding 4}
    (add-map svc form part)

    (vector? part) ;; ["&:hover" :px-4] subgroup
    (add-group svc form part)

    :else
    (add-warning svc form ::invalid-part part)))

(defn process-form [svc {:keys [form] :as form-info}]
  (-> form-info
      (assoc :sel "&" :block [] :rules {} :at-rules {} :warnings [])
      (reduce-> #(add-part svc %1 %2) form)
      (dissoc :stack :sel :block)))

(defn flatten-prefix-lists [form]
  (let [prefix-parts (take-while #(not (keyword? %)) form)
        prefix-count (count prefix-parts)
        args (drop prefix-count form)]

    (if-not (even? (count args))
      (throw (ex-info "failed to parse ns require" {:form form}))
      (let [args-map (apply array-map args)]
        (if (= 1 prefix-count)
          [(assoc args-map :require (first form))]
          (let [[prefix & suffixes] prefix-parts]
            (when (string? prefix)
              (throw (ex-info "failed to parse ns require, string requires can't have prefix lists" {:form form})))

            (loop [expanded
                   (if-not (seq args)
                     []
                     [(assoc args-map :require (first form))])
                   suffixes suffixes]

              (if-not (seq suffixes)
                expanded
                (let [[part & more] suffixes]
                  (cond
                    (symbol? part)
                    (recur
                      (conj expanded {:require (symbol (str prefix "." part))})
                      more)

                    (sequential? part)
                    (recur
                      (into expanded
                        (flatten-prefix-lists
                          (cons
                            (symbol (str prefix "." (first part)))
                            (rest part))))
                      more)

                    :else
                    (throw (ex-info "failed to parse ns form, unexpected prefix part" {:form form :part part}))))))))))))

(defn parse-ns-require-part [state part]
  (cond
    (symbol? part)
    (update state :requires conj part)

    (sequential? part)
    (reduce
      (fn [state {:keys [require as-alias as refer rename] :as opts}]
        ;; non-loading, only [some.ns :as-alias foo], do not add to :requires
        (if (and (= 2 (count opts)) as-alias)
          (update state :require-aliases assoc as-alias require)
          (-> state
              (update :requires conj require)
              (cond->
                as
                (update :require-aliases assoc as require)
                as-alias
                (update :require-aliases assoc as-alias require)
                (seq refer)
                (reduce->
                  ;; not constructing a {css shadow.css/css} qualified symbol here
                  ;; since require may be a string in CLJS
                  #(assoc-in %1 [:refer %2] {:require require :sym %2})
                  refer)
                (seq rename)
                (reduce-kv->
                  (fn [state from to]
                    (update state :refer
                      (fn [m]
                        (-> m
                            (cond->
                              ;; only remove from refer if it came from same form
                              ;; [a :refer (css)]
                              ;; [b :refer (css) :rename {css c}]
                              ;; FIXME: verify this is actually what clojure does?
                              (contains? refer from)
                              (dissoc from))
                            (assoc to {:require require :sym from})))))
                  rename)))))
      state
      (flatten-prefix-lists part))

    ;; ignore parts like  :reload :reload-all etc
    :else
    state))

(defn parse-ns-require-form [state [require-kw & parts]]
  (reduce parse-ns-require-part state parts))

(defn parse-ns [state form]
  (let [[_ ns maybe-meta]
        form

        ;; FIXME: should this filter shadow.css/* already? don't really need other meta
        ns-meta
        (merge
          ;; don't care about the reader metadata, only added stuff
          (dissoc (meta ns) :source :line :column :end-line :end-column)
          (when (map? maybe-meta)
            maybe-meta))

        ns-requires
        (->> form
             (drop 2)
             (filter #(and (list? %) (= :require (first %)))))]

    (-> state
        (assoc :ns ns :ns-meta ns-meta)
        (reduce-> parse-ns-require-form ns-requires))))

(defn find-css-calls [state form]
  (cond
    (map? form)
    (reduce find-css-calls state (vals form))

    (list? form)
    (case (first form)
      ;; (ns foo {:maybe "meta") ...)
      ns
      (parse-ns state form)

      ;; don't traverse into (comment ...)
      comment
      state

      ;; thing we actually look for
      ;; FIXME: make this use require-aliases/refers, should find aliased uses
      ;; FIXME: also make this extensible in some way so it can find
      ;; other forms that maybe expand to css via some macro
      css
      (update state :css conj
        (-> (meta form)
            (dissoc :source)
            ;; want [:px-4] instead of (css :px-4)
            ;; don't really care about the (css ...) part later
            ;; other forms also maybe won't have this
            (assoc :form (vec (rest form)))))

      ;; any other list
      (reduce find-css-calls state form))

    ;; sets, vectors
    (coll? form)
    (reduce find-css-calls state form)

    :else
    state))

(defn find-css-in-source [src]
  ;; shortcut if src doesn't contain any css, faster than parsing all forms
  (let [has-css? (str/index-of src "(css")
        reader (reader-types/source-logging-push-back-reader src)
        eof #?(:clj (Object.) :cljs (js-obj))]

    (loop [ns-found false
           state
           {:css []
            :ns nil
            :ns-meta {}
            :require-aliases {}
            :requires []}]

      (let [form
            (binding
              [reader/*default-data-reader-fn*
               (fn [tag data] data)

               ;; used for ::alias/keywords
               reader/*alias-map*
               (fn [sym]
                 (get (:require-aliases state) sym sym))

               ;; used for ::keywords
               ;; don't know actual ns until ns form is parsed
               *ns*
               (create-ns (or (:ns state) 'user))]

              (try
                (reader/read {:eof eof :read-cond :preserve} reader)
                (catch #?(:clj Exception :cljs :default) e
                  (throw (ex-info "failed to parse ns" {:ns (:ns state)} e)))))]

        (if (identical? form eof)
          state

          (let [next-state (find-css-calls state form)]
            (cond
              (and (not ns-found) (not (:ns next-state)))
              ;; do not continue without ns form being first, just don't look for css
              next-state

              (not has-css?)
              next-state

              :else
              (recur true next-state))))))))