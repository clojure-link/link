(ns link.websocket
  (:use [link tcp util])
  (:import [java.util List])
  (:import [io.netty.buffer ByteBuf Unpooled])
  (:import [io.netty.handler.codec.http
            HttpResponseEncoder
            HttpRequestDecoder
            HttpObjectAggregator
            FullHttpRequest]
           [io.netty.handler.codec.http.websocketx
            WebSocketServerProtocolHandler
            WebSocketServerProtocolHandler$ServerHandshakeStateEvent
            WebSocketFrame
            TextWebSocketFrame
            BinaryWebSocketFrame
            PingWebSocketFrame
            PongWebSocketFrame
            CloseWebSocketFrame
            WebSocketServerHandshaker
            WebSocketClientHandshaker
            WebSocketClientHandshakerFactory
            WebSocketVersion]
           [io.netty.channel
            Channel
            ChannelPipeline
            ChannelHandlerContext
            SimpleChannelInboundHandler
            ChannelInboundHandlerAdapter
            ChannelOutboundHandlerAdapter]
           [io.netty.util AttributeKey]))

(defonce ^:private handshake-info-key
  (AttributeKey/valueOf "link-websocket-handshake-info"))

(defn handshake-info
  ([^Channel ch]
   (.get (.attr ch handshake-info-key)))
  ([^Channel ch info]
   (.set (.attr ch handshake-info-key) info)))

(defn websocket-handshake-message-handler []
  (proxy [ChannelInboundHandlerAdapter] []
    (channelRead [ctx req]
      (when (instance? FullHttpRequest req)
        ;; req is actually a FullHttpRequest object
        (let [uri (.getUri ^FullHttpRequest req)
              headers (.headers ^FullHttpRequest req)]
          (handshake-info (.channel ctx) {:uri uri
                                          :headers headers}))
        ;; FIXME: use pipeline.remove(ctx) which is faster
        (.remove (.pipeline ctx) this))
      (proxy-super channelRead ctx req))))

(defn server-protocol-handler
  "A server protocol handler that let user to process ping/pong"
  [path subprotocols allow-extensions max-frame-size]
  (proxy [WebSocketServerProtocolHandler]
      [path subprotocols allow-extensions max-frame-size]
    (decode [^ChannelHandlerContext ctx
             ^WebSocketFrame frame
             ^List out]
      (if (instance? CloseWebSocketFrame frame)
        (let [handshaker (proxy-super getHandshaker ctx)]
          (.retain frame)
          (.close ^WebSocketServerHandshaker handshaker
                  (.channel ctx) frame))
        (.add out (.retain frame))))))

(defn websocket-codecs
  [path & {:keys [max-frame-size
                  allow-extensions
                  subprotocols]
           :or {max-frame-size 65536
                allow-extensions false}}]
  ;; web socket handler is of course stateful
  [(fn [_] (HttpRequestDecoder.))
   (fn [_] (HttpObjectAggregator. 65536))
   (fn [_] (HttpResponseEncoder.))
   (fn [_] (websocket-handshake-message-handler))
   (fn [_] (server-protocol-handler path subprotocols
                                   allow-extensions max-frame-size))])

(defn text [^String s]
  (TextWebSocketFrame. s))

(defn binary [^ByteBuf bytes]
  (BinaryWebSocketFrame. bytes))

(defn binary2 [^bytes bytes]
  (binary (Unpooled/wrappedBuffer ^bytes bytes)))

(defn ping
  ([] (ping (Unpooled/buffer 0)))
  ([^ByteBuf payload] (PingWebSocketFrame. payload)))

(defn pong
  ([] (pong (Unpooled/buffer 0)))
  ([^ByteBuf payload] (PongWebSocketFrame. payload)))

(defn closing
  ([] (CloseWebSocketFrame.))
  ([^long status ^String reason] (CloseWebSocketFrame. (int status) reason)))

;; make message receive handler
(make-handler-macro text)
(make-handler-macro binary)
(make-handler-macro unserialized)
(make-handler-macro ping)
(make-handler-macro pong)
(make-handler-macro error)
(make-handler-macro open)
(make-handler-macro close)
(make-handler-macro handshake-complete)
(make-handler-macro event)

(defmacro create-handler1 [sharable & body]
  `(let [handlers# (merge ~@body)]
     (proxy [SimpleChannelInboundHandler] []
       (isSharable [] ~sharable)
       (channelActive [^ChannelHandlerContext ctx#]
         (when-let [handler# (:on-open handlers#)]
           (handler# (.channel ctx#)))
         (.fireChannelActive ctx#))

       (channelInactive [^ChannelHandlerContext ctx#]
         (when-let [handler# (:on-close handlers#)]
           (handler# (.channel ctx#)))
         (.fireChannelInactive ctx#))

       (exceptionCaught [^ChannelHandlerContext ctx#
                         ^Throwable e#]
         (if-let [handler# (:on-error handlers#)]
           (handler# (.channel ctx#) e#)
           (.fireExceptionCaught  ctx# e#)))

       (userEventTriggered [^ChannelHandlerContext ctx# evt#]
         (if (and (= evt# WebSocketServerProtocolHandler$ServerHandshakeStateEvent/HANDSHAKE_COMPLETE)
                  (:on-handshake-complete handlers#))
           ((:on-handshake-complete handlers#) (.channel ctx#))
           (.fireUserEventTriggered ctx# evt#)))

       (channelRead0 [^ChannelHandlerContext ctx# msg#]
         (let [ch# (.channel ctx#)]
           (cond
            (and (map? msg#) (:on-unserialized handlers#))
            ((:on-unserialized handlers#) ch# msg#)

            (and (instance? TextWebSocketFrame msg#) (:on-text handlers#))
            ((:on-text handlers#) ch# (.text ^TextWebSocketFrame msg#))

            (and (instance? BinaryWebSocketFrame msg#) (:on-binary handlers#))
            ((:on-binary handlers#) ch# (.content ^BinaryWebSocketFrame msg#))

            (instance? PingWebSocketFrame msg#)
            (if (:on-ping handlers#)
              ((:on-ping handlers#) ch# (.content ^PingWebSocketFrame msg#))
              (do
                (.retain ^PingWebSocketFrame msg#)
                (.writeAndFlush ch# (pong (.content ^PingWebSocketFrame msg#)))))

            (and (instance? PongWebSocketFrame msg#) (:on-pong handlers#))
            ((:on-pong handlers#) ch# (.content ^PongWebSocketFrame msg#))))))))

(defmacro create-websocket-handler [& body]
  `(create-handler1 true ~@body))

(defmacro create-stateful-websocket-handler [& body]
  `(fn [] (create-handler1 false ~@body)))

(def ^:private byte-array-type (type (byte-array 0)))
(def websocket-auto-frame-encoder
  (proxy [ChannelOutboundHandlerAdapter] []
    (write [ctx msg promise]
      (cond
       (instance? String msg)
       (.write ^ChannelHandlerContext ctx (text msg) promise)

       (instance? ByteBuf msg)
       (.write ^ChannelHandlerContext ctx (binary msg) promise)

       (= byte-array-type (type msg))
       (.write ^ChannelHandlerContext ctx
               (binary2 msg) promise)

       :else
       ;; super magic: get rid of reflection warning in proxy-super
       ;; https://groups.google.com/forum/#!msg/clojure/x8F-WYIk2Nk/gEo_o69e6xsJ
       (let [^ChannelOutboundHandlerAdapter this this]
         (proxy-super write ctx msg promise))))

    (isSharable []
      true)))
