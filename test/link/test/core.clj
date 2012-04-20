(ns link.test.core
  (:refer-clojure :exclude [send])
  (:use [link.core])
  (:use [clojure.test])
  (:import [java.net InetSocketAddress])
  (:import [org.jboss.netty.channel
            ChannelHandlerContext
            MessageEvent
            ExceptionEvent]))

(def msg-event
  (reify MessageEvent
    (getMessage [this] "msg")
    (getRemoteAddress [this] (InetSocketAddress. "127.0.0.1" 2104))
    (getChannel [this])
    (getFuture [this])))

(def exp-event
  (reify ExceptionEvent
    (getCause [this] (Exception.))
    (getChannel [this])
    (getFuture [this])))

(def ch-handle-ctx
  (reify ChannelHandlerContext
    (canHandleDownstream [this])
    (canHandleUpstream [this])
    (getAttachment [this])
    (getChannel [this] nil)
    (getHandler [this])
    (getName [this])
    (getPipeline [this])
    (sendDownstream [this e])
    (sendUpstream [this e])
    (setAttachment [this o])))

(deftest test-handler
  (let [test-handler (create-handler
                      (on-open [ch] true)
                      (on-close [ch] true)
                      (on-message [ch msg addr] true)
                      (on-error [ch e] true)
                      (on-connected [ch] true)
                      (on-disconnected [ch] true))
        ]
    (is (nil? (.channelClosed test-handler ch-handle-ctx nil)))
    (is (nil? (.channelConnected test-handler ch-handle-ctx nil)))
    (is (nil? (.channelDisconnected test-handler ch-handle-ctx nil)))
    (is (nil? (.channelOpen test-handler ch-handle-ctx nil)))
    (is (nil? (.exceptionCaught test-handler ch-handle-ctx exp-event)))
    (is (nil? (.messageReceived test-handler ch-handle-ctx msg-event)))))

