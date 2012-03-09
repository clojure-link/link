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

(defmacro make-handler [& body]
  `(let [handlers# (merge ~@body)]
     (reify SimpleChannelUpstreamHandler
       (channelClosed [this ctx e]
         (if-let [handler# (:on-close handlers#)]
           (apply handler# ctx e)))
       (channelConnected [this ctx e]
         (if-let [handler# (:on-connected handlers#)]
           (apply handler# ctx e)))
       (channelDisconnected [this ctx e]
         (if-let [handler# (:on-disconnected handlers#)]
           (apply handler# ctx e)))
       (channelOpen [this ctx e]
         (if-let [handler# (:on-open handlers#)]
           (apply handler# ctx e)))
       (exceptionCaught [this ctx e]
         (if-let [handler# (:on-error handlers#)]
           (apply handler# ctx e)))
       (messageReceived [this ctx e]
         (if-let [handler# (:on-message handlers#)]
           (apply handler# ctx e))))))


