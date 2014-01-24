(ns link.test.core
  (:refer-clojure :exclude [send])
  (:use [link.core])
  (:use [clojure.test])
  (:import [java.net InetSocketAddress])
  (:import [io.netty.channel ChannelHandlerContext]))

(def msg "msg")

(def exp (Exception.))

(def ch-handle-ctx
  (reify ChannelHandlerContext
    (channel [this])
    (fireChannelRead [this msg])
    (fireChannelActive [this])
    (fireChannelInactive [this])
    (fireExceptionCaught [this e])))

(deftest test-handler
  (let [test-handler (create-handler
                      (on-active [ch] true)
                      (on-inactive [ch] true)
                      (on-message [ch msg] true)
                      (on-error [ch e] true))]
    (is (nil? (.channelActive test-handler ch-handle-ctx)))
    (is (nil? (.channelInactive test-handler ch-handle-ctx)))
    (is (nil? (.exceptionCaught test-handler ch-handle-ctx exp)))
    (is (nil? (.channelRead0 test-handler ch-handle-ctx msg)))))
