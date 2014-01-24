(ns link.core
  (:refer-clojure :exclude [send])
  (:import [clojure.lang IDeref])
  (:import [java.net InetSocketAddress])
  (:import [java.nio.channels ClosedChannelException])
  (:import [io.netty.channel
            Channel
            ChannelHandlerContext
            SimpleChannelInboundHandler]))

(defprotocol MessageChannel
  (send [this msg])
  (valid? [this])
  (channel-addr [this])
  (remote-addr [this])
  (close [this]))

(defn- client-channel-valid? [^Channel ch]
  (and ch (.isActive ch)))

(deftype ClientSocketChannel [ch-agent factory-fn]
  MessageChannel
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

(deftype SimpleWrappedSocketChannel [^Channel ch]
  MessageChannel
  (send [this msg]
    (.write ch msg))
  (channel-addr [this]
    (.localAddress ch))
  (remote-addr [this]
    (.remoteAddress ch))
  (close [this]
    (.close ch))
  (valid? [this]
    (and ch (.isActive ch))))

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

(defmacro create-handler [& body]
  `(let [handlers# (merge ~@body)]
     (proxy [SimpleChannelInboundHandler] []
       (channelActive [^ChannelHandlerContext ctx#]
         (when-let [handler# (:on-active handlers#)]
           (handler# (SimpleWrappedSocketChannel. (.channel ctx#))))
         (.fireChannelActive ctx#))

       (channelInactive [^ChannelHandlerContext ctx#]
         (when-let [handler# (:on-inactive handlers#)]
           (handler# (SimpleWrappedSocketChannel. (.channel ctx#))))
         (.fireChannelInactive ctx#))

       (exceptionCaught [^ChannelHandlerContext ctx#
                         ^Throwable e#]
         (when-let [handler# (:on-error handlers#)]
           (let [ch# (SimpleWrappedSocketChannel. (.channel ctx#))]
             (handler# ch# e#)))
         (.fireExceptionCaught  ctx# e#))

       (channelRead0 [^ChannelHandlerContext ctx# msg#]
         (when-let [handler# (:on-message handlers#)]
           (let [ch# (SimpleWrappedSocketChannel. (.channel ctx#))]
             (handler# ch# msg#)))
         (.fireChannelRead ctx# msg#)))))
