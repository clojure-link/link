(ns link.core
  (:refer-clojure :exclude [send])
  (:use [link.util :only [make-handler-macro]])
  (:import [io.netty.channel
            Channel
            ChannelFuture
            ChannelHandlerContext
            ChannelOption
            SimpleChannelInboundHandler])
  (:import [io.netty.channel.socket.nio NioSocketChannel])
  (:import [io.netty.util.concurrent GenericFutureListener]))

(defprotocol LinkMessageChannel
  (id [this])
  (send [this msg])
  (send* [this msg cb])
  (valid? [this])
  (channel-addr [this])
  (remote-addr [this])
  (close [this]))

(defn- client-channel-valid? [^Channel ch]
  (and ch (.isActive ch)))

(defn- channel-id [^Channel ch]
  (str (.localAddress ch) "->" (.remoteAddress ch)))

(deftype ClientSocketChannel [ch-agent factory-fn]
  LinkMessageChannel
  (id [this]
    (channel-id @ch-agent))
  (send [this msg]
    (send* this msg nil))
  (send* [this msg cb]
    (clojure.core/send ch-agent
                       (fn [ch]
                         (let [valid (client-channel-valid? ch)
                               ch- (if valid ch (try (factory-fn)
                                                     (catch Exception e ch)))
                               cf (if (client-channel-valid? ch-)
                                    (.writeAndFlush ^Channel ch- msg))]
                           (when (and cf cb)
                             (.addListener ^ChannelFuture cf
                                           (reify GenericFutureListener
                                             (operationComplete [this f]
                                               (cb f)))))
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
  (id [this]
    (channel-id this))
  (send [this msg]
    (send* this msg nil))
  (send* [this msg cb]
    (let [cf (.writeAndFlush this msg)]
      (when cb
        (.addListener ^ChannelFuture cf (reify GenericFutureListener
                                          (operationComplete [this f] (cb f)))))))
  (channel-addr [this]
    (.localAddress this))
  (remote-addr [this]
    (.remoteAddress this))
  (close [this]
    (.close this))
  (valid? [this]
    (.isActive this)))

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
           (.fireExceptionCaught ctx# e#)))

       (channelRead0 [^ChannelHandlerContext ctx# msg#]
         (if-let [handler# (:on-message handlers#)]
           (handler# (.channel ctx#) msg#)
           (.fireChannelRead ctx# msg#))))))

(defmacro create-handler [& body]
  `(create-handler0 true ~@body))

(defmacro create-stateful-handler [& body]
  `(fn [] (create-handler0 false ~@body)))

(def channel-option
  {
   :allocator ChannelOption/ALLOCATOR
   :rcvbuf-allocator ChannelOption/RCVBUF_ALLOCATOR
   :message-size-estimator ChannelOption/MESSAGE_SIZE_ESTIMATOR

   :connect-timeout-millis ChannelOption/CONNECT_TIMEOUT_MILLIS
   :max-messages-per-read ChannelOption/MAX_MESSAGES_PER_READ
   :write-spin-count ChannelOption/WRITE_SPIN_COUNT
   :write-buffer-high-water-mark ChannelOption/WRITE_BUFFER_HIGH_WATER_MARK
   :write-buffer-low-water-mark ChannelOption/WRITE_BUFFER_LOW_WATER_MARK

   :allow-half-closure ChannelOption/ALLOW_HALF_CLOSURE
   :auto-read ChannelOption/AUTO_READ

   :so-broadcast ChannelOption/SO_BROADCAST
   :so-keepalive ChannelOption/SO_KEEPALIVE
   :so-sndbuf ChannelOption/SO_SNDBUF
   :so-rcvbuf ChannelOption/SO_RCVBUF
   :so-reuseaddr ChannelOption/SO_REUSEADDR
   :so-linger ChannelOption/SO_LINGER
   :so-backlog ChannelOption/SO_BACKLOG
   :so-timeout ChannelOption/SO_TIMEOUT

   :ip-tos ChannelOption/IP_TOS
   :ip-multicast-addr ChannelOption/IP_MULTICAST_ADDR
   :ip-multicast-if ChannelOption/IP_MULTICAST_IF
   :ip-multicast-ttl ChannelOption/IP_MULTICAST_TTL
   :ip-multicast-loop-disabled ChannelOption/IP_MULTICAST_LOOP_DISABLED

   :tcp-nodelay ChannelOption/TCP_NODELAY
   })
