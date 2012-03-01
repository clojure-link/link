(ns link.codec
  (:refer-clojure :exclude [byte])
  (:import [org.jboss.netty.buffer
            ChannelBuffer
            ChannelBuffers]))

(defmacro defcodec [sym encoder-fn decoder-fn]
  `(defn ~sym [& options#]
     {:encoder (partial ~encoder-fn options#)
      :decoder (partial ~decoder-fn options#)}))

;; macro to improve codec readability
(defmacro encoder [args & body]
  `(fn ~args ~@body))

(defmacro decoder [args & body]
  `(fn ~args ~@body))

(defcodec byte
  (encoder [_ data buffer]
           (.writeByte buffer data)
           buffer)
  (decoder [_ buffer]
           (.readByte buffer)))

(defcodec int16
  (encoder [_ data buffer]
           (.writeShort buffer data)
           buffer)
  (decoder [_ buffer]
           (.readShort buffer)))

(defcodec uint16
  (encoder [_ data buffer]
           (.writeShort buffer data)
           buffer)
  (decoder [_ buffer]
           (.readUnsignedShort buffer)))

(defcodec int24
  (encoder [_ data buffer]
           (.writeMedium buffer data)
           buffer)
  (decoder [_ buffer]
           (.readMedium buffer)))

(defcodec uint24
  (encoder [_ data buffer]
           (.writeMedium buffer data)
           buffer)
  (decoder [_ buffer]
           (.readUnsignedMedium buffer)))

(defcodec int32
  (encoder [_ data buffer]
           (.writeInt buffer data)
           buffer)
  (decoder [_ buffer]
           (.readInt buffer)))

(defcodec uint32
  (encoder [_ data buffer]
           (.writeInt buffer data)
           buffer)
  (decoder [_ buffer]
           (.readUnsignedInt buffer)))

(defcodec int64
  (encoder [_ data buffer]
           (.writeLong buffer data)
           buffer)
  (decoder [_ buffer]
           (.readLong buffer)))

(defcodec unit64
  (encoder [_ data buffer]
           (.writeLong buffer data)
           buffer)
  (decoder [_ buffer]
           (.readUnsignedLong buffer)))

(defn- find-delimiter [^ChannelBuffer src ^bytes delim]
  (loop [sindex (.readerIndex src) dindex 0]
    (if (= sindex (.writerIndex src))
      -1
      (if (= (.getByte src sindex) (aget delim dindex))
        (if (= dindex (alength delim))
          (- sindex (.readerIndex src))
          (recur (inc sindex) (inc dindex)))
        (recur (inc sindex) 0)))))

(defcodec string
  (encoder [options ^String data buffer]
           (let [{prefix :prefix encoding :encoding delimiter :delimiter} options
                 encoding (name encoding)
                 bytes (.getBytes data encoding)]
             (cond
              ;; length prefix string
              (not (nil? prefix))
              (do
                ((:encoder prefix ) nil (alength bytes) buffer)
                (.writeBytes buffer ^bytes bytes))
              ;; delimiter based string
              (not (nil? delimiter))
              (do
                (.writeBytes buffer ^bytes bytes)
                (.writeBytes buffer ^bytes (.getBytes delimiter encoding)))))
           buffer)
  (decoder [options buffer]
           (let [{prefix :prefix encoding :encoding delimiter :delimiter} options
                 encoding (name encoding)]
             (cond
              ;; length prefix string
              (not (nil? prefix))
              (do
                (let [byte-length ((:decoder prefix) nil buffer)
                      bytes (byte-array byte-length)]
                  (.readBytes buffer ^bytes bytes)
                  (String. bytes encoding)))

              ;; delimiter based string
              (not (nil? delimiter))
              (do
                (let [dbytes (.getBytes delimiter encoding)
                      dlength (find-delimiter buffer dbytes)
                      slength (- dlength (alength dbytes))
                      sbytes (byte-array slength)]
                  (.readBytes buffer ^bytes sbytes)
                  (String. sbytes encoding)))))))

;;TODO

(defn encode [codec data]
  (let [buffer (ChannelBuffers/dynamicBuffer)]
    
    buffer))

(defn decode [codec buffer]
  )

