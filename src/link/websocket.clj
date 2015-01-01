(ns link.websocket
  (:use [link tcp util])
  (:import [java.util List])
  (:import [io.netty.buffer ByteBuf Unpooled])
  (:import [io.netty.handler.codec.http
            HttpResponseEncoder
            HttpRequestDecoder
            HttpObjectAggregator])
  (:import [io.netty.handler.codec.http.websocketx
            WebSocketServerProtocolHandler
            WebSocketFrame
            TextWebSocketFrame
            BinaryWebSocketFrame
            PingWebSocketFrame
            PongWebSocketFrame
            CloseWebSocketFrame
            WebSocketServerHandshaker
            WebSocketClientHandshaker
            WebSocketClientHandshakerFactory
            WebSocketVersion])
  (:import [io.netty.channel
            Channel
            ChannelPipeline
            ChannelHandlerContext
            SimpleChannelInboundHandler
            ChannelOutboundHandlerAdapter]))

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
  [(fn [] (HttpRequestDecoder.))
   (fn [] (HttpObjectAggregator. 65536))
   (fn [] (HttpResponseEncoder.))
   (fn [] (server-protocol-handler path subprotocols
                                  allow-extensions max-frame-size))])

(defn text [^String s]
  (TextWebSocketFrame. s))

(defn binary [^ByteBuf bytes]
  (BinaryWebSocketFrame. bytes))

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
(make-handler-macro ping)
(make-handler-macro pong)
(make-handler-macro error)
(make-handler-macro open)
(make-handler-macro close)
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
         (if-let [handler# (:on-event handlers#)]
           (handler# (.channel ctx#) evt#)
           (.fireUserEventTriggered ctx# evt#)))

       (channelRead0 [^ChannelHandlerContext ctx# msg#]
         (let [ch# (.channel ctx#)]
           (cond
            (and (instance? TextWebSocketFrame msg#) (:on-text handlers#))
            ((:on-text handlers#) ch# (.text ^TextWebSocketFrame msg#))

            (and (instance? BinaryWebSocketFrame msg#) (:on-binary handlers#))
            ((:on-binary handlers#) ch# (.content ^BinaryWebSocketFrame msg#))

            (instance? PingWebSocketFrame msg#)
            (if (:on-ping handlers#)
              ((:on-ping handlers#) ch# (.content ^PingWebSocketFrame msg#))
              (.writeAndFlush ch# (pong (.content ^PongWebSocketFrame msg#))))

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
               (binary (Unpooled/wrappedBuffer ^bytes msg)) promise)

       :else
       ;; super magic: get rid of reflection warning in proxy-super
       ;; https://groups.google.com/forum/#!msg/clojure/x8F-WYIk2Nk/gEo_o69e6xsJ
       (let [^ChannelOutboundHandlerAdapter this this]
         (proxy-super write ctx msg promise))))

    (isSharable []
      true)))
