(ns link.codec
  (:refer-clojure :exclude [byte float double])
  (:require [link.util :as util]
            [clojure.walk :refer [postwalk]])
  (:import [java.util List])
  (:import [io.netty.buffer ByteBuf Unpooled])
  (:import [io.netty.channel
            ChannelHandlerContext
            ChannelPromise])
  (:import [io.netty.handler.codec
            ByteToMessageCodec
            ByteToMessageDecoder
            MessageToByteEncoder]
           [io.netty.util ReferenceCountUtil]))

(defn unpooled-buffer []
  (Unpooled/buffer))

(defn try-retain [frame]
  (postwalk #(ReferenceCountUtil/retain %) frame))

(defn try-release [frame]
  (postwalk #(ReferenceCountUtil/release %) frame))

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
              (when (>= (.readableBytes buffer#) ~size)
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

;; little endian versions of multibyte primitive codecs
(primitive-codec int16-le 2 writeShortLE readShortLE)
(primitive-codec uint16-le 2 writeShortLE readUnsignedShortLE)
(primitive-codec int24-le 3 writeMediumLE readMediumLE)
(primitive-codec uint24-le 3 writeMediumLE readUnsignedMediumLE)
(primitive-codec int32-le 4 writeIntLE readIntLE)
(primitive-codec uint32-le 4 writeIntLE readUnsignedIntLE)
(primitive-codec int64-le 8 writeLongLE readLongLE)
(primitive-codec float-le 4 writeFloatLE readFloatLE)
(primitive-codec double-le 8 writeDoubleLE readDoubleLE)

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
               (and (nil? delimiter) (nil? prefix))
               (throw (IllegalArgumentException. "Neither :delimiter nor :prefix provided for string codec"))
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
               (and (nil? delimiter) (nil? prefix))
               (throw (IllegalArgumentException. "Neither :delimiter nor :prefix provided for string codec"))
               ;; length prefix string
               (nil? delimiter)
               (do
                 (when-let [byte-length ((:decoder prefix) buffer)]
                   (when-not (> byte-length (.readableBytes buffer))
                     (let [bytes (byte-array byte-length)]
                       (.readBytes buffer ^bytes bytes)
                       (String. bytes encoding)))))

               ;; delimiter based string
               (nil? prefix)
               (let [dbytes (.getBytes ^String delimiter encoding)
                     dmlength (alength ^bytes dbytes)
                     dlength (find-delimiter buffer dbytes)
                     slength (- dlength dmlength)]
                 (let [sbytes (byte-array slength)]
                   (.readBytes buffer ^bytes sbytes)
                   ;; move readerIndex
                   (.readerIndex buffer (+ dmlength (.readerIndex buffer)))
                   (String. sbytes encoding)))))))

(defcodec byte-block
  (encoder [options ^ByteBuf data ^ByteBuf buffer]
           (let [{prefix :prefix encode-length-fn :encode-length-fn} options
                 encode-length-fn (or encode-length-fn identity)
                 byte-length (if (nil? data) 0 (.readableBytes data))
                 encoded-length (encode-length-fn byte-length)]
             ((:encoder prefix) encoded-length buffer)
             (when-not (nil? data)
               (.writeBytes buffer ^ByteBuf data)
               (.release ^ByteBuf data))
             buffer))
  (decoder [options ^ByteBuf buffer]
           (let [{prefix :prefix decode-length-fn :decode-length-fn} options
                 decode-length-fn (or decode-length-fn identity)
                 byte-length (decode-length-fn ((:decoder prefix) buffer))]
             (when-not (or (nil? byte-length)
                           (> byte-length (.readableBytes buffer)))
               (.readRetainedSlice buffer byte-length)))))

(def ^{:private true} reversed-map
  (memoize
   (fn [m]
     (apply hash-map (mapcat #(vector (val %) (key %)) m)))))

(defcodec enum
  (encoder [options data ^ByteBuf buffer]
           (let [[codec mapping] options
                 mapping (if (util/derefable? mapping) @mapping mapping)
                 value (get mapping data)]
             ((:encoder codec) value buffer)))
  (decoder [options ^ByteBuf buffer]
           (let [[codec mapping] options
                 mapping (if (util/derefable? mapping) @mapping mapping)
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
               [head body]
               (do
                 (try-release body)
                 nil)))))

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
                   (recur (rest c) (conj r r0))
                   (do
                     (try-release r)
                     nil)))))))

(defcodec counted
  (encoder [options data ^ByteBuf buffer]
           (let [length (count data)
                 {length-codec :prefix body-codec :body} options]
             ((:encoder length-codec) length buffer)
             (doseq [frm data]
               ((:encoder body-codec) frm buffer))
             buffer))
  (decoder [options ^ByteBuf buffer]
           (let [{length-codec :prefix body-codec :body} options
                 length ((:decoder length-codec) buffer)]
             (when length
               (loop [idx 0 results []]
                 (if (== idx length)
                   results
                   (if-let [data ((:decoder body-codec) buffer)]
                     (recur (inc idx) (conj results data))
                     (do
                       (try-release results)
                       nil))))))))

(defcodec const
  (encoder [options data ^ByteBuf buffer]
           buffer)
  (decoder [options ^ByteBuf buffer]
           (first options)))

(defn encode*
  ([codec data ^ByteBuf buffer]
   ((:encoder codec) data buffer)))

(defn decode* [codec ^ByteBuf buffer]
  ((:decoder codec) buffer))

(defn netty-encoder [codec]
  (when codec
    (fn [_]
      (proxy [MessageToByteEncoder] []
        (encode [^ChannelHandlerContext ctx
                 msg
                 ^ByteBuf buf]
          (encode* codec msg buf))))))

(defn netty-decoder [codec]
  (when codec
    (fn [_]
      (proxy [ByteToMessageDecoder]  []
        (decode [ctx ^ByteBuf buf ^List out]
          (.markReaderIndex buf)
          (if-let [frame (decode* codec buf)]
            (.add out frame)
            (.resetReaderIndex buf)))))))

(defn netty-codec [codec]
  (when codec
    (fn [_]
      (proxy [ByteToMessageCodec] []
        (encode [^ChannelHandlerContext ctx
                 msg
                 ^ByteBuf buf]
          (encode* codec msg buf))
        (decode [ctx ^ByteBuf buf ^List out]
          (.markReaderIndex buf)
          (if-let [frame (decode* codec buf)]
            (.add out frame)
            (.resetReaderIndex buf)))))))
