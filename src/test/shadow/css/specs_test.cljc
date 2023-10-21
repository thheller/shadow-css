(ns shadow.css.specs-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [shadow.css.specs :as specs]))

(deftest generate-id-test
  (testing "Complex ID"
    (is (= "sc--1789522625"
           (specs/generate-id [:px-4 :my-2
                               "pass"
                               :c-text-1
                               [:&hover :py-10]
                               [:md+ :px-6
                                ["@media print" :px-2]]
                               [:lg+ :px-8
                                [:&hover :py-12]]]))))

  (testing "Order of rules"
    (testing "doesn't matter within selectors/main list"
      (is (= (specs/generate-id [:px-4 :my-2
                                 [:&hover :m-4 :py-10 :px-4]
                                 [:lg+ :px-8
                                  [:&hover :p-4 :py-12]]])
             (specs/generate-id [:px-4 :my-2
                                 [:&hover :py-10 :m-4 :px-4]
                                 [:lg+ :px-8
                                  [:&hover :py-12 :p-4]]]))))

    (testing "does matter for the whole structure"
      (is (not= (specs/generate-id [:m-4
                                    [:&hover :px-4]])
                (specs/generate-id [:px-4
                                    [:&hover :m-4]]))))))
