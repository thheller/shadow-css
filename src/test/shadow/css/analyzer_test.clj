(ns shadow.css.analyzer-test
  (:require
    [clojure.string :as str]
    [clojure.pprint :refer (pprint)]
    [clojure.test :as ct :refer (deftest is)]
    [shadow.css.analyzer :as ana]
    [shadow.css.build :as build]
    [shadow.css.specs :as s]
    [clojure.java.io :as io]))

(deftest analyze-form
  (pprint
    (ana/process-form
      (build/start)
      {:ns 'foo.bar
       :line 1
       :column 2
       :form '(css :px-4 :my-2
                "pass"
                :c-text-1
                [:&hover :py-10]
                [:md+ :px-6
                 ["@media print" :px-2]]
                [:lg+ :px-8
                 [:&hover :py-12]])})))


;; TODO: CLI API for these?
;; or just clojure API to be used from REPL/tools.build?

;; library side index generation to be included in jar
(deftest index-src-main
  (time
    (tap>
      (-> (build/start)
          (build/index-path (io/file "src" "main") {})))))

;; project side to generate actual css
(deftest build-src-main
  (time
    (tap>
      (-> (build/start)
          (build/index-path "src/main" {})
          #_(build/generate '{:main {:include [shadow.cljs.ui.*]}})))))

(defn parse-tailwind [[tbody tbody-attrs & rows]]
  (reduce
    (fn [all row]
      (let [[_ td-key td-val] row
            [_ _ key] td-key
            [_ _ val] td-val

            rules
            (->> (str/split val #"\n")
                 (reduce
                   (fn [rules prop+val]
                     (let [[prop val] (str/split prop+val #": " 2)]
                       (assoc rules (keyword prop) (subs val 0 (dec (count val))))))
                   {}
                   ))]

        (assoc all (keyword key) rules)))
    {}
    rows))

(deftest test-parse-tailwind
  (let [s
        nil

        rules (parse-tailwind s)]
    (doseq [rule (sort (keys rules))]
      (println (str rule " " (pr-str (get rules rule))))
      )))

(deftest test-parse-ns
  (let [state
        {:requires []
         :require-aliases {}
         :refers {}}

        form
        '(ns ^{:foo "bar"} foo.bar
           {:bar "foo"}
           (:require
             just-a-sym
             [shadow.grove :refer (css << defc)]
             [shadow.css :rename {css c}]
             [just-another-sym]
             [some.thing :as x]
             [some.other-thing :refer (x)]
             [not.loading :as-alias foo]
             ["js-require" :as npm]
             ["js-require-blank"]
             ;; FIXME: not even sure this is valid, prefix lists are terrible
             [bad.prefix list]
             [terrible.prefix
              [list
               [nested :as n]
               :as y]
              :as z]))]

    (pprint (ana/parse-ns state form))
    ))


(deftest test-parse-finds-namespaced-keywords
  (let [result (ana/find-css-in-source "(ns what (:require [thing :as x])) (css ::x/foo ::baz)")]

    (pprint result)
    ))