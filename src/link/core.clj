(ns link.core
  (:refer-clojure :exclude [send])
  (:import [java.net InetSocketAddress])
  (:import [io.netty.channel
            Channel
            ChannelHandlerContext
            SimpleChannelInboundHandler])
  (:import [io.netty.channel.socket.nio NioSocketChannel]))


(defprotocol LinkMessageChannel
  (send [this msg])
  (valid? [this])
  (channel-addr [this])
  (remote-addr [this])
  (close [this]))

(defn- client-channel-valid? [^Channel ch]
  (and ch (.isActive ch)))

(deftype ClientSocketChannel [ch-agent factory-fn]
  LinkMessageChannel
  (send [this msg]
    (clojure.core/send ch-agent
                       (fn [ch]
                         (let [valid (client-channel-valid? ch)
                               ch- (if valid ch (factory-fn))]
                           (.writeAndFlush ^Channel ch- msg)
                           ch-))))
  (channel-addr [this]
    (.localAddress ^Channel @ch-agent))
  (remote-addr [this]
    (.remoteAddress ^Channel @ch-agent))
  (close [this]
    (clojure.core/send ch-agent
                       (fn [ch]
                         (.close ^Channel ch)))
    (await ch-agent))
  (valid? [this]
    (client-channel-valid? @ch-agent)))

(extend-protocol LinkMessageChannel
  NioSocketChannel
  (send [this msg]
    (.writeAndFlush this msg))
  (channel-addr [this]
    (.localAddress this))
  (remote-addr [this]
    (.remoteAddress this))
  (close [this]
    (.close this))
  (valid? [this]
    (.isActive this)))

(defmacro ^{:private true} make-handler-macro [evt]
  (let [handler-name (str "on-" evt)
        symbol-name (symbol handler-name)
        args-vec-sym (symbol "args-vec")
        body-sym (symbol "body")]
    `(defmacro ~symbol-name [~args-vec-sym & ~body-sym]
       `{(keyword ~~handler-name) (fn ~~args-vec-sym ~@~body-sym)})))

(make-handler-macro message)
(make-handler-macro error)
(make-handler-macro active)
(make-handler-macro inactive)

(defmacro create-handler0 [sharable & body]
  `(let [handlers# (merge ~@body)]
     (proxy [SimpleChannelInboundHandler] []
       (isSharable [] ~sharable)
       (channelActive [^ChannelHandlerContext ctx#]
         (when-let [handler# (:on-active handlers#)]
           (handler# (.channel ctx#)))
         (.fireChannelActive ctx#))

       (channelInactive [^ChannelHandlerContext ctx#]
         (when-let [handler# (:on-inactive handlers#)]
           (handler# (.channel ctx#)))
         (.fireChannelInactive ctx#))

       (exceptionCaught [^ChannelHandlerContext ctx#
                         ^Throwable e#]
         (if-let [handler# (:on-error handlers#)]
           (handler# (.channel ctx#) e#)
           (.fireExceptionCaught  ctx# e#)))

       (channelRead0 [^ChannelHandlerContext ctx# msg#]
         (if-let [handler# (:on-message handlers#)]
           (handler# (.channel ctx#) msg#)
           (.fireChannelRead ctx# msg#))))))

(defmacro create-handler [& body]
  `(create-handler0 true ~@body))

(defmacro create-stateful-handler [& body]
  `(fn [] (create-handler0 false ~@body)))
