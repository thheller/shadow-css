(ns shadow.css.analyzer-test
  (:require
    [clojure.string :as str]
    [clojure.pprint :refer (pprint)]
    [clojure.test :as ct :refer (deftest is)]
    [shadow.css.index :as index]
    [shadow.css.analyzer :as ana]
    [shadow.css.build :as build]
    [shadow.css.specs :as s]
    ))

(deftest analyze-form
  (pprint
    (ana/process-form
      (build/start {})
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
      (-> (index/create)
          (index/add-path "src/main" {})
          (index/write-to "tmp/css/shadow-css-index.edn")))))

;; project side to generate actual css
(deftest build-src-main
  (time
    (tap>
      (-> (build/start {})
          (build/index-path "src/main" {})
          #_(build/generate '{:output-dir "tmp/css"
                              :chunks {:main {:include [shadow.cljs.ui.*]}}})))))

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