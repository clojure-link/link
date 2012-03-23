(ns link.codec
  (:refer-clojure :exclude [byte float double])
  (:import [java.nio ByteBuffer])
  (:import [org.jboss.netty.buffer
            ChannelBuffer
            ChannelBuffers])
  (:import [org.jboss.netty.channel
            Channels
            ChannelDownstreamHandler
            ChannelUpstreamHandler
            MessageEvent]))

(defmacro defcodec [sym encoder-fn decoder-fn]
  `(defn ~sym [& options#]
     {:encoder (partial ~encoder-fn options#)
      :decoder (partial ~decoder-fn options#)}))

;; macro to improve codec readability
(defmacro encoder [args & body]
  `(fn ~args ~@body))

(defmacro decoder [args & body]
  `(fn ~args ~@body))

(defmacro primitive-codec [sname writer-fn reader-fn]
  `(defcodec ~sname
     (encoder [_# data# buffer#]
              (. buffer# ~writer-fn data#)
              buffer#)
     (decoder [_# buffer#]
              (. buffer# ~reader-fn))))

(primitive-codec byte writeByte readByte)
(primitive-codec int16 writeShort readShort)
(primitive-codec uint16 writeShort readUnsignedShort)
(primitive-codec int24 writeMedium readMedium)
(primitive-codec uint24 writeMedium readUnsignedMedium)
(primitive-codec int32 writeInt readInt)
(primitive-codec uint32 writeInt readUnsignedInt)
(primitive-codec int64 writeLong readLong)
(primitive-codec float writeFloat readFloat)
(primitive-codec double writeDouble readDouble)

(defn- find-delimiter [^ChannelBuffer src ^bytes delim]
  (loop [sindex (.readerIndex src) dindex 0]
    (if (= sindex (.writerIndex src))
      -1
      (if (= (.getByte src sindex) (aget delim dindex))
        (if (= dindex (- (alength delim) 1))
          (+ (- sindex (.readerIndex src)) 1)
          (recur (inc sindex) (inc dindex)))
        (recur (inc sindex) 0)))))

(defcodec string
  (encoder [options ^String data buffer]
           (let [{:keys [prefix encoding delimiter]} options
                 encoding (name encoding)
                 bytes (.getBytes data encoding)]
             (cond
              ;; length prefix string
              (nil? delimiter)
              (do
                ((:encoder prefix) (alength bytes) buffer)
                (.writeBytes buffer ^bytes bytes))
              ;; delimiter based string
              (nil? prefix)
              (do
                (.writeBytes buffer ^bytes bytes)
                (.writeBytes buffer ^bytes
                             (.getBytes delimiter encoding)))))
           buffer)
  (decoder [options buffer]
           (let [{:keys [prefix encoding delimiter]} options
                 encoding (name encoding)]
             (cond
              ;; length prefix string
              (nil? delimiter)
              (do
                (let [byte-length ((:decoder prefix) buffer)
                      bytes (byte-array byte-length)]
                  (.readBytes buffer ^bytes bytes)
                  (String. bytes encoding)))

              ;; delimiter based string
              (nil? prefix)
              (do
                (let [dbytes (.getBytes delimiter encoding)
                      dlength (find-delimiter buffer dbytes)
                      slength (- dlength (alength dbytes))
                      sbytes (byte-array slength)]
                  (.readBytes buffer ^bytes sbytes)
                  (String. sbytes encoding)))))))

(defcodec byte-block
  (encoder [options ^ByteBuffer data buffer]
           (let [{prefix :prefix} options
                 byte-length (.remaining data)]
             ((:encoder prefix) byte-length buffer)
             (.writeBytes buffer ^ByteBuffer data)
             buffer))
  (decoder [options buffer]
           (let [{prefix :prefix} options
                 byte-length ((:decoder prefix) buffer)
                 local-buffer (ByteBuffer/allocate byte-length)]
             (.readBytes buffer ^ByteBuffer local-buffer)
             (.rewind local-buffer)
             local-buffer)))

(def ^{:private true} reversed-map
  (memoize
   (fn [m]
     (apply hash-map (mapcat #(vector (val %) (key %)) m)))))

(defcodec enum
  (encoder [options data buffer]
           (let [[codec mapping] options
                 value (get mapping data)]
             ((:encoder codec) value buffer)))
  (decoder [options buffer]
           (let [[codec mapping] options
                 mapping (reversed-map mapping)
                 value ((:decoder codec) buffer)]
             (get mapping value))))

(defcodec header
  (encoder [options data buffer]
           (let [[enumer children] options
                 head (first data)
                 body (second data)
                 body-codec (get children head)]
             ((:encoder enumer) head buffer)
             ((:encoder body-codec) body buffer)
             buffer))
  (decoder [options buffer]
           (let [[enumer children] options
                 head ((:decoder enumer) buffer)
                 body ((:decoder (get children head)) buffer)]
             [head body])))

(defcodec frame
  (encoder [options data buffer]
           (let [codecs options]
             (dorun (map #((:encoder %1) %2 buffer) codecs data))
             buffer))
  (decoder [options buffer]
           (let [codecs options]
             (doall (map #((:decoder %) buffer) codecs)))))

(defn encode
  ([codec data] (encode codec data (ChannelBuffers/dynamicBuffer)))
  ([codec data buffer]
     ((:encoder codec) data buffer)))

(defn decode [codec buffer]
  ((:decoder codec) buffer))

(defn netty-encoder [codec]
  (reify ChannelDownstreamHandler
    (handleDownstream [this ctx e]
      (if-not (instance? MessageEvent e)
        (.sendDownstream ctx e)
        (do
          (let [data (.getMessage e)
                buffer (encode codec data)]
            (Channels/write ctx (.getFuture e) buffer (.getRemoteAddress e))))))))

(defn netty-decoder [codec]
  (reify ChannelUpstreamHandler
    (handleUpstream [this ctx e]
      (if-not (instance? MessageEvent e)
        (.sendUpstream ctx e)
        (let [buffer (.getMessage e)
              data (decode codec buffer)]
          (Channels/fireMessageReceived ctx data (.getRemoteAddress e)))))))

