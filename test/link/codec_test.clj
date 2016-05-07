(ns link.codec-test
  (:refer-clojure :exclude [byte float double])
  (:use [clojure.test])
  (:use [link.codec])
  (:import [io.netty.buffer Unpooled]))

(deftest test-codecs
  (are [data codec]
       (let [buffer (Unpooled/buffer 256)]
         (is (= data (decode* codec (encode* codec data buffer)))))

       1 (byte)
       1 (int16)
       10 (uint16)
       10 (int24)
       10 (uint24)
       100 (int32)
       100 (uint32)
       (long 1000) (int64)
       (clojure.core/float 32.455) (float)
       (clojure.core/double 32.455) (double)
       "helloworld" (string :prefix (int16) :encoding :utf-8)
       "link" (string :encoding :utf-8 :delimiter "\r\n")
       :hello (enum (int16) {:hello 1 :world 2})
       [:hello "world"] (header
                         (enum (int16)
                               {:hello 1 :world 2})
                         {:hello (string :encoding :utf-8 :prefix (byte))
                          :world (int16)})
       [1 1 (long 1) "helloworld"] (frame
                                    (byte)
                                    (int16)
                                    (int64)
                                    (string :prefix (int32) :encoding :ascii))
       [1 :hello "yes"] (frame
                         (const 1)
                         (const :hello)
                         (const "yes"))
       [[1] [2] [3]] (counted :prefix (int16) :body (frame (byte)))))

(deftest test-nil-results
  (let [buffer (Unpooled/buffer)
        codec (string :prefix (int32) :encoding :utf-8)]
    (.writeInt buffer 50)
    (.writeBytes buffer (.getBytes "Hello World" "UTF-8"))


    (is (nil? (decode* codec buffer)))))
