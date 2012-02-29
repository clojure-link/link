(ns link.codec
  (:refer-clojure :exclude [byte])
  (:import [org.jboss.netty.buffer
            ChannelBuffer
            ChannelBuffers]))

(defmacro defcodec [sym encoder-fn decoder-fn]
  `(defn ~sym [& options#]
     {:encoder (partial ~encoder-fn options#)
      :decoder (partial ~decoder-fn options#)}))

(defcodec byte
  ;; encoder
  (fn [_ data buffer]
    (.writeByte buffer data)
    buffer)
  ;; decoder
  (fn [_ buffer]
    (.readByte buffer)))

(defcodec int16
  ;; encoder
  (fn [_ data buffer]
    (.writeShort buffer data)
    buffer)
  ;; decoder
  (fn [_ buffer]
    (.readShort buffer)))

(defcodec uint16
  ;; encoder
  (fn [_ data buffer]
    (.writeUnsignedShort buffer data)
    buffer)
  ;; decoder
  (fn [_ buffer]
    (.readUnsignedShort buffer)))

;;TODO

(defn encode [codec data]
  (let [buffer (ChannelBuffers/dynamicBuffer)]
    
    buffer))

(defn decode [codec buffer]
  )

