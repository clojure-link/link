(ns link.websocket-test
  (:use [link.websocket])
  (:use [clojure.test])
  (:import [io.netty.channel ChannelHandlerContext]
           [io.netty.buffer UnpooledByteBufAllocator]))

(def ch-handle-ctx
  (reify ChannelHandlerContext
    (channel [this])
    (fireChannelRead [this msg])
    (fireChannelActive [this])
    (fireChannelInactive [this])
    (fireExceptionCaught [this e])))


(deftest test-handler
  (let [mark (atom false)
        echo-msg "hje"
        test-handler (create-websocket-handler
                      (on-text [ch msg] (swap! mark not))
                      (on-binary [ch bytes]))]
    (.channelRead0 test-handler ch-handle-ctx (text UnpooledByteBufAllocator/DEFAULT echo-msg))
    (is @mark)))
