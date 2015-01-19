(ns link.codec
  (:refer-clojure :exclude [byte float double])
  (:import [java.nio ByteBuffer])
  (:import [java.util List])
  (:import [io.netty.buffer
            ByteBuf
            Unpooled])
  (:import [io.netty.channel
            ChannelHandlerContext
            ChannelPromise])
  (:import [io.netty.handler.codec
            ByteToMessageDecoder
            MessageToByteEncoder]))

(defmacro defcodec [sym encoder-fn decoder-fn]
  `(defn ~sym [& options#]
     {:encoder (partial ~encoder-fn options#)
      :decoder (partial ~decoder-fn options#)}))

;; macro to improve codec readability
(defmacro encoder [args & body]
  `(fn ~args ~@body))

(defmacro decoder [args & body]
  `(fn ~args ~@body))

(defmacro primitive-codec [sname size writer-fn reader-fn]
  `(defcodec ~sname
     (encoder [_# data# ^ByteBuf buffer#]
              (. buffer# ~writer-fn data#)
              buffer#)
     (decoder [_# ^ByteBuf buffer#]
              (if (>= (.readableBytes buffer#) ~size)
                (. buffer# ~reader-fn)))))

(primitive-codec byte 1 writeByte readByte)
(primitive-codec int16 2 writeShort readShort)
(primitive-codec uint16 2 writeShort readUnsignedShort)
(primitive-codec int24 3 writeMedium readMedium)
(primitive-codec uint24 3 writeMedium readUnsignedMedium)
(primitive-codec int32 4 writeInt readInt)
(primitive-codec uint32 4 writeInt readUnsignedInt)
(primitive-codec int64 8 writeLong readLong)
(primitive-codec float 4 writeFloat readFloat)
(primitive-codec double 8 writeDouble readDouble)

(defn- find-delimiter [^ByteBuf src ^bytes delim]
  (loop [sindex (.readerIndex src) dindex 0]
    (if (= sindex (.writerIndex src))
      -1
      (if (= ^Byte (.getByte src sindex) ^Byte (aget delim dindex))
        (if (= dindex (- (alength delim) 1))
          (+ (- sindex (.readerIndex src)) 1)
          (recur (inc sindex) (inc dindex)))
        (recur (inc sindex) 0)))))

(defcodec string
  (encoder [options ^String data ^ByteBuf buffer]
           (let [{:keys [prefix encoding delimiter]} options
                 encoding (name encoding)
                 bytes (.getBytes (or data "") encoding)]
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
                             (.getBytes ^String delimiter encoding)))))
           buffer)
  (decoder [options ^ByteBuf buffer]
           (let [{:keys [prefix encoding delimiter]} options
                 encoding (name encoding)]
             (cond
              ;; length prefix string
              (nil? delimiter)
              (do
                (if-let [byte-length ((:decoder prefix) buffer)]
                  (if-not (> byte-length (.readableBytes buffer))
                    (let [bytes (byte-array byte-length)]
                      (.readBytes buffer ^bytes bytes)
                      (String. bytes encoding)))))

              ;; delimiter based string
              (nil? prefix)
              (do
                (let [dbytes (.getBytes ^String delimiter encoding)
                      dlength (find-delimiter buffer dbytes)
                      slength (- dlength (alength ^bytes dbytes))
                      sbytes (byte-array slength)]
                  (.readBytes buffer ^bytes sbytes)
                  (String. sbytes encoding)))))))

(defcodec byte-block
  (encoder [options ^ByteBuffer data ^ByteBuf buffer]
           (let [{prefix :prefix encode-length-fn :encode-length-fn} options
                 encode-length-fn (or encode-length-fn identity)
                 byte-length (if (nil? data) 0 (.remaining data))
                 encoded-length (encode-length-fn byte-length)]
             ((:encoder prefix) encoded-length buffer)
             (if-not (nil? data)
               (.writeBytes buffer ^ByteBuffer data))
             buffer))
  (decoder [options ^ByteBuf buffer]
           (let [{prefix :prefix decode-length-fn :decode-length-fn} options
                 decode-length-fn (or decode-length-fn identity)
                 byte-length (decode-length-fn ((:decoder prefix) buffer))]
             (if-not (or (nil? byte-length)
                         (> byte-length (.readableBytes buffer)))
               (let [local-buffer (ByteBuffer/allocate byte-length)]
                 (.readBytes buffer ^ByteBuffer local-buffer)
                 (.rewind local-buffer)
                 local-buffer)))))

(def ^{:private true} reversed-map
  (memoize
   (fn [m]
     (apply hash-map (mapcat #(vector (val %) (key %)) m)))))

(defcodec enum
  (encoder [options data ^ByteBuf buffer]
           (let [[codec mapping] options
                 value (get mapping data)]
             ((:encoder codec) value buffer)))
  (decoder [options ^ByteBuf buffer]
           (let [[codec mapping] options
                 mapping (reversed-map mapping)
                 value ((:decoder codec) buffer)]
             (get mapping value))))

(defcodec header
  (encoder [options data ^ByteBuf buffer]
           (let [[enumer children] options
                 head (first data)
                 body (second data)
                 body-codec (get children head)]
             ((:encoder enumer) head buffer)
             ((:encoder body-codec) body buffer)
             buffer))
  (decoder [options ^ByteBuf buffer]
           (let [[enumer children] options
                 head ((:decoder enumer) buffer)
                 body (and head ;; body is nil if head is nil
                       ((:decoder (get children head)) buffer))]
             (if-not (nil? body)
               [head body]))))

(defcodec frame
  (encoder [options data ^ByteBuf buffer]
           (let [codecs options]
             (dorun (map #((:encoder %1) %2 buffer) codecs data))
             buffer))
  (decoder [options ^ByteBuf buffer]
           (let [codecs options]
             (loop [c codecs r []]
               (if (empty? c)
                 r
                 (if-let [r0 ((:decoder (first c)) buffer)]
                   (recur (rest c) (conj r r0))))))))

(defn encode*
  ([codec data ^ByteBuf buffer]
     ((:encoder codec) data buffer)))

(defn decode* [codec ^ByteBuf buffer]
  ((:decoder codec) buffer))

(defn netty-encoder [codec]
  (if codec
    (fn [_]
      (proxy [MessageToByteEncoder] []
        (encode [^ChannelHandlerContext ctx
                 msg
                 ^ByteBuf buf]
          (encode* codec msg buf))))))

(defn netty-decoder [codec]
  (if codec
    (fn [_]
      (proxy [ByteToMessageDecoder]  []
        (decode [ctx ^ByteBuf buf ^List out]
          (.markReaderIndex buf)
          (let [frame (decode* codec buf)]
            (if frame
              (.add out frame)
              (.resetReaderIndex buf))))))))
