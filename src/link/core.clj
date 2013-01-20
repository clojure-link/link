(ns link.core
  (:refer-clojure :exclude [send])
  (:import [clojure.lang IDeref]
           [java.net InetSocketAddress]
           [java.nio.channels ClosedChannelException]
           [org.jboss.netty.bootstrap ServerBootstrap ClientBootstrap]
           [org.jboss.netty.channel Channel ChannelHandlerContext MessageEvent
            ExceptionEvent WriteCompletionEvent SimpleChannelUpstreamHandler]
           [org.jboss.netty.handler.execution ExecutionHandler
            MemoryAwareThreadPoolExecutor OrderedMemoryAwareThreadPoolExecutor]))

(defrecord Server [#^ServerBootstrap bootstrap channel])

(defrecord Client [#^ClientBootstrap bootstrap])

(defprotocol MessageChannel
  (send [this msg])
  (valid? [this])
  (channel-addr [this])
  (remote-addr [this])
  (close [this]))

(defn- client-channel-valid? [^Channel ch]
  (and (not (nil? ch))
       (.isOpen ch)
       (.isBound ch)
       (.isConnected ch)))

(deftype ClientSocketChannel [ch-agent factory-fn]
  MessageChannel
  (send [this msg]
    (clojure.core/send ch-agent
                       (fn [ch]
                         (let [valid (client-channel-valid? ch)
                               ch- (if valid ch (factory-fn))]
                           (.write ^Channel ch- msg)
                           ch-))))
  (channel-addr [this]
    (.getLocalAddress ^Channel @ch-agent))
  (remote-addr [this]
    (.getRemoteAddress ^Channel @ch-agent))
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
    (.getLocalAddress ch))
  (remote-addr [this]
    (.getRemoteAddress ch))
  (close [this]
    (.close ch))
  (valid? [this]
    (and (not (nil? ch)) (.isOpen ch) (.isBound ch))))

(defmacro ^{:private true} make-handler-macro [evt]
  (let [handler-name (str "on-" evt)
        symbol-name (symbol handler-name)
        args-vec-sym (symbol "args-vec")
        body-sym (symbol "body")]
    `(defmacro ~symbol-name [~args-vec-sym & ~body-sym]
       `{(keyword ~~handler-name) (fn ~~args-vec-sym ~@~body-sym)})))

(make-handler-macro open)
(make-handler-macro close)
(make-handler-macro message)
(make-handler-macro error)
(make-handler-macro connected)
(make-handler-macro disconnected)
(make-handler-macro write-complete)

(defmacro create-handler [& body]
  `(let [handlers# (merge ~@body)]
     (proxy [SimpleChannelUpstreamHandler] []
       (channelClosed [^ChannelHandlerContext ctx# e#]
         (if-let [handler# (:on-close handlers#)]
           (handler# (SimpleWrappedSocketChannel. (.getChannel ctx#)))
           (.sendUpstream ctx# e#)))
       (channelConnected [^ChannelHandlerContext ctx# e#]
         (if-let [handler# (:on-connected handlers#)]
           (handler# (SimpleWrappedSocketChannel. (.getChannel ctx#)))
           (.sendUpstream ctx# e#)))
       (channelDisconnected [^ChannelHandlerContext ctx# e#]
         (if-let [handler# (:on-disconnected handlers#)]
           (handler# (SimpleWrappedSocketChannel. (.getChannel ctx#)))
           (.sendUpstream ctx# e#)))
       (channelOpen [^ChannelHandlerContext ctx# e#]
         (if-let [handler# (:on-open handlers#)]
           (handler# (SimpleWrappedSocketChannel. (.getChannel ctx#)))
           (.sendUpstream ctx# e#)))
       
       (exceptionCaught [^ChannelHandlerContext ctx#
                         ^ExceptionEvent e#]
         (when-let [handler# (:on-error handlers#)]
           (let [ch# (SimpleWrappedSocketChannel. (.getChannel ctx#))
                 exp# (.getCause e#)]
             (handler# ch# exp#)))
         (.sendUpstream  ctx# e#))
       
       (messageReceived [^ChannelHandlerContext ctx#
                         ^MessageEvent e#]
         (when-let [handler# (:on-message handlers#)]
           (let [message# (.getMessage e#)
                 addr# (.getRemoteAddress e#)
                 ch# (SimpleWrappedSocketChannel. (.getChannel ctx#))]
             (handler# ch# message# addr#)))
         (.sendUpstream ctx# e#))

       (writeComplete [^ChannelHandlerContext ctx#
                       ^WriteCompletionEvent e#]
         (when-let [handler# (:on-write-complete handlers#)]
           (let [amount# (.getWrittenAmount e#)
                 ch# (SimpleWrappedSocketChannel. (.getChannel ctx#))]
             (handler# ch# amount#)))
         (.sendUpstream ctx# e#)))))

(defn threaded-handler [ordered]
  (let [core-size 20
        max-channel-memory 0 ;;unlimited
        max-total-memory 0 ;;unlimited
        ]
   (ExecutionHandler.
    (if ordered
      (OrderedMemoryAwareThreadPoolExecutor.
       core-size max-channel-memory max-total-memory)
      (MemoryAwareThreadPoolExecutor.
       core-size max-channel-memory max-total-memory)))))


