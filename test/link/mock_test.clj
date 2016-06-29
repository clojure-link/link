(ns link.mock-test
  (:require [link.mock :refer [mock-channel]]
            [link.core :refer :all]
            [clojure.test :refer :all]))

(deftest test-mock-channel
  (testing "testing mock channel"
    (let [laddr "127.0.0.1:8080"
          raddr "127.0.0.1:55888"
          chid "0x12345"
          ch (mock-channel {:channel-addr laddr
                            :remote-addr raddr
                            :channel-id chid})]
      (is (= laddr (channel-addr ch)))
      (is (= raddr (remote-addr ch)))
      (send! ch :a)
      (send! ch :b)
      (send!* ch :c (fn [future]))
      (is (valid? ch))
      (close! ch)
      (is (not (valid? ch)))
      (is (= [:a :b :c] @ch)))))
