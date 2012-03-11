(ns link.test.core
  (:use [link.core])
  (:use [clojure.test]))


(deftest test-handler
  (let [test-handler (create-handler
                      (on-open [ctx e] true)
                      (on-close [ctx e] true)
                      (on-message [ctx e] true)
                      (on-error [ctx e] true)
                      (on-connected [ctx e] true)
                      (on-disconnected [ctx e] true))
        ]
    (is (nil? (.channelClosed test-handler nil nil)))
    (is (nil? (.channelConnected test-handler nil nil)))
    (is (nil? (.channelDisconnected test-handler nil nil)))
    (is (nil? (.channelOpen test-handler nil nil)))
    (is (nil? (.exceptionCaught test-handler nil nil)))
    (is (nil? (.messageReceived test-handler nil nil)))))

