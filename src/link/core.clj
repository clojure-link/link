(ns link.core
  (:import [org.jboss.netty.channel SimpleChannelUpstreamHandler]))

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
         (when-let [handler# (:on-close handlers#)]
           (handler# ctx# e#)))
       (channelConnected [ctx# e#]
         (when-let [handler# (:on-connected handlers#)]
           (handler# ctx# e#)))
       (channelDisconnected [ctx# e#]
         (when-let [handler# (:on-disconnected handlers#)]
           (handler# ctx# e#)))
       (channelOpen [ctx# e#]
         (when-let [handler# (:on-open handlers#)]
           (handler# ctx# e#)))
       (exceptionCaught [ctx# e#]
         (when-let [handler# (:on-error handlers#)]
           (handler# ctx# e#)))
       (messageReceived [ctx# e#]
         (when-let [handler# (:on-message handlers#)]
           (handler# ctx# e#))))))
