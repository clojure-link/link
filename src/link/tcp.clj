(ns link.tcp
  (:refer-clojure :exclude [send])
  (:use [link.core])
  (:use [link.codec :only [netty-encoder netty-decoder]])
  (:import [java.net InetSocketAddress])
  (:import [java.util.concurrent Executors])
  (:import [java.nio.channels ClosedChannelException])
  (:import [org.jboss.netty.bootstrap
            ClientBootstrap
            ServerBootstrap])
  (:import [org.jboss.netty.channel
            Channels
            ChannelPipelineFactory
            Channel
            ChannelHandlerContext
            ])
  (:import [org.jboss.netty.channel.socket.nio
            NioServerSocketChannelFactory
            NioClientSocketChannelFactory])
  (:import [org.jboss.netty.handler.execution
            ExecutionHandler
            MemoryAwareThreadPoolExecutor])
  (:import [link.core WrappedSocketChannel]))

(defn- create-pipeline [& handlers]
  (reify ChannelPipelineFactory
    (getPipeline [this]
      (let [pipeline (Channels/pipeline)]
        (doseq [i (range (count handlers))]
          (.addLast pipeline (str "handler-" i) (nth handlers i)))
        pipeline))))

(defn reconnector [^ClientBootstrap bootstrap
                   ^InetSocketAddress addr
                   chref]
  (create-handler
   (on-error ([exp]
                (when (instance? ClosedChannelException exp)
                  (let [chfuture (.connect bootstrap addr)
                        ch (.. chfuture
                               awaitUninterruptibly
                               getChannel)]
                    (reset! chref ch)))))))

(defn- start-tcp-server [port handler encoder decoder threaded? tcp-options]
  (let [factory (NioServerSocketChannelFactory.
                 (Executors/newCachedThreadPool)
                 (Executors/newCachedThreadPool))
        bootstrap (ServerBootstrap. factory)
        handlers (if-not threaded?
                   [encoder decoder handler]
                   [encoder decoder 
                    (ExecutionHandler.
                     (MemoryAwareThreadPoolExecutor. 20 0 0))
                    handler])
        pipeline (apply create-pipeline handlers)]
    (.setPipelineFactory bootstrap pipeline)
    (.setOptions bootstrap tcp-options)
    (.bind bootstrap (InetSocketAddress. port))))

(defn tcp-server [port handler
                  & {:keys [encoder decoder codec threaded? tcp-options]
                     :or {encoder nil
                          decoder nil
                          codec nil
                          threaded? false
                          tcp-options {}}}]
  (let [encoder (netty-encoder (or encoder codec))
        decoder (netty-decoder (or decoder codec))]
    (start-tcp-server port handler
                      encoder decoder
                      threaded?
                      tcp-options)))

(defn tcp-client [host port handler
                  & {:keys [encoder decoder codec auto-reconnect tcp-options]
                     :or {encoder nil
                          decoder nil
                          codec nil
                          auto-reconnect false
                          tcp-options {}}}]
  (let [encoder (netty-encoder (or encoder codec))
        decoder (netty-decoder (or decoder codec))
        bootstrap (ClientBootstrap.
                   (NioClientSocketChannelFactory.
                    (Executors/newCachedThreadPool)
                    (Executors/newCachedThreadPool)))
        addr (InetSocketAddress. ^String host ^Integer port)
        chref (atom nil)
        handlers (if auto-reconnect
                   [encoder decoder
                    (reconnector bootstrap addr chref) handler]
                   [encoder decoder handler])
        pipeline (apply create-pipeline handlers)]
    (.setPipelineFactory bootstrap pipeline)
    (.setOptions bootstrap tcp-options)
    (let [ch (.. (.connect bootstrap addr)
                 awaitUninterruptibly
                 getChannel)]
      (reset! chref ch)
      (WrappedSocketChannel. chref))))



