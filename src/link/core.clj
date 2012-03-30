(ns link.core
  (:refer-clojure :exclude [send])
  (:import [java.net InetSocketAddress])
  (:import [org.jboss.netty.channel
            Channel
            SimpleChannelUpstreamHandler]))

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

(defmacro create-handler [& body]
  `(let [handlers# (merge ~@body)]
     (proxy [SimpleChannelUpstreamHandler] []
       (channelClosed [ctx# e#]
         (if-let [handler# (:on-close handlers#)]
           (handler# ctx# e#)
           (.sendUpstream ctx# e#)))
       (channelConnected [ctx# e#]
         (if-let [handler# (:on-connected handlers#)]
           (handler# ctx# e#)
           (.sendUpstream ctx# e#)))
       (channelDisconnected [ctx# e#]
         (if-let [handler# (:on-disconnected handlers#)]
           (handler# ctx# e#)
           (.sendUpstream ctx# e#)))
       (channelOpen [ctx# e#]
         (if-let [handler# (:on-open handlers#)]
           (handler# ctx# e#)
           (.sendUpstream ctx# e#)))
       (exceptionCaught [ctx# e#]
         (if-let [handler# (:on-error handlers#)]
           (handler# ctx# e#)
           (.sendUpstream ctx# e#)))
       (messageReceived [ctx# e#]
         (if-let [handler# (:on-message handlers#)]
           (handler# ctx# e#)
           (.sendUpstream ctx# e#))))))

(defprotocol MessageChannel
  (send [this msg])
  (close [this]))

(deftype WrappedSocketChannel [ch-ref]
  MessageChannel
  (send [this msg]
    (.write ^Channel @ch-ref msg))
  (close [this]
    (.close ^Channel @ch-ref)))


