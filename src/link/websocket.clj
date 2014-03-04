(ns link.websocket
  (:refer-clojure :exclude [send])
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
            WebSocketServerHandshaker])
  (:import [io.netty.channel
            ChannelHandlerContext
            SimpleChannelInboundHandler]))

(defn server-protocol-handler
  "A server protocol handler that let user to process ping/pong"
  [path subprotocols]
  (proxy [WebSocketServerProtocolHandler]
      [path subprotocols]
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
  ([path] (websocket-codecs path nil))
  ([path subprotocols]
     ;; web socket handler is of course stateful
     [(fn [] (HttpRequestDecoder.))
      (fn [] (HttpObjectAggregator. 65536))
      (fn [] (HttpResponseEncoder.))
      (fn [] (server-protocol-handler path subprotocols))]))

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
            ((:on-pong handlers#) ch# (.content ^PongWebSocketFrame msg#))

            :else (.fireChannelRead ctx# msg#)))))))

(defmacro create-websocket-handler [& body]
  `(create-handler1 true ~@body))

(defmacro create-stateful-websocket-handler [& body]
  `(fn [] (create-handler1 false ~@body)))
