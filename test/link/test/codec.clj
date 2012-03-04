(ns link.test.codec
  (:refer-clojure :exclude [byte float double])
  (:use [clojure.test])
  (:use [link.codec])
  (:import [org.jboss.netty.buffer ChannelBuffers]))

(deftest test-encode-and-decode
  (are [data codec]
       (let [{encoder :encoder decoder :decoder} (codec)
             buffer (ChannelBuffers/buffer 256)]
         (is (== data (decoder (encoder data buffer)))))

       1 byte
       1 int16
       10 uint16
       10 int24
       10 uint24
       100 int32
       100 uint32
       (long 1000) int64
       (clojure.core/float 32.455) float
       (clojure.core/double 32.455) double))
