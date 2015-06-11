(ns link.threads-test
  (:require [clojure.test :refer :all]
            [link.threads :refer :all]))

(deftest test-thread-factory
  (testing "the generated thread name should starts with fixed prefix"
    (let [tf (prefix-thread-factory "link")
          t (.newThread tf (fn []))]
      (is (= "link-1" (.getName t))))))
