(ns shadow.css.specs
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]))

(s/def ::alias keyword?)

(defn non-empty-string? [s]
  (and (string? s) (not (str/blank? s))))

(s/def ::str-part
  ;; str or alias
  #(or (non-empty-string? %) (keyword? %)))

(s/def ::str-concat
  (s/coll-of ::str-part :kind list?))

(s/def ::val
  (s/or
    :string non-empty-string?
    :number number?
    :alias ::alias
    :concat ::str-concat))

(s/def ::passthrough
  non-empty-string?)

(s/def ::map
  (s/map-of simple-keyword? ::val))

(defn sub-selector? [x]
  (and (non-empty-string? x)
       (or (str/starts-with? x "@")
           (str/index-of x "&"))))

(s/def ::group-selector
  (s/or
    :string
    sub-selector?
    :alias
    ::alias
    ))

(s/def ::group
  (s/and
    vector?
    (s/cat
      :sel
      ::group-selector
      :parts
      (s/+ ::part))))

(s/def ::part
  (s/or
    :alias
    ::alias

    :map
    ::map

    :group
    ::group))

(s/def ::root-part
  (s/or
    :alias
    ::alias

    :passthrough
    ::passthrough

    :map
    ::map

    :group
    ::group))

(s/def ::class-def
  (s/cat
    :parts
    (s/+ ::root-part)))

(defn conform! [[_ & body :as form]]
  (let [conformed (s/conform ::class-def body)]
    (when (= conformed ::s/invalid)
      (throw (ex-info "failed to parse class definition"
               (assoc (s/explain-data ::class-def body)
                 :tag ::invalid-class-def
                 :input body))))
    conformed))

(defn conform [[_ & body :as form]]
  (let [conformed (s/conform ::class-def body)]
    (if (= conformed ::s/invalid)
      {:parts [] :invalid true :body body :spec (s/explain-data ::class-def body)}
      conformed)))

(defn generate-id [ns ns-meta line column]
  (str (or (:shadow.css/alias ns-meta)
           (-> (str ns)
               (str/replace #"\." "_")
               (munge)))
       "__"
       "L" line
       "_"
       "C" column))