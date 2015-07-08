(ns sparkling.conf-test
  (:require [sparkling.conf :as conf]
            [clojure.test :refer :all]))

(deftest spark-conf  ; about setting k/v into spark-conf
  (let [c (conf/spark-conf)]
    (testing "spark-conf returns a SparkConf object"
      (is (= (class c) org.apache.spark.SparkConf)))

    (testing "setting master works"
      (is (= (conf/get (conf/master c "local") "spark.master") "local")))

    (testing "setting app-name works"
      (is (= (conf/get (conf/app-name c "test") "spark.app.name") "test")))))
