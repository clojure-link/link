(ns link.tcp
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
            ExceptionEvent
            ])
  (:import [org.jboss.netty.channel.socket.nio
            NioServerSocketChannelFactory
            NioClientSocketChannelFactory])
  (:import [link.channel WrappedSocketChannel]))

(defn- create-pipeline [handlers encoder decoder]
  (reify ChannelPipelineFactory
    (getPipeline [this]
      (let [pipeline (Channels/pipeline)]
        (when-not (nil? decoder)
          (.addLast pipeline "decoder" (netty-decoder decoder)))
        (when-not (nil? encoder)
          (.addLast pipeline "encoder" (netty-encoder encoder)))
        (if (sequential? handlers)
          (doseq [i (range (count handlers))]
            (.addLast pipeline (str "handler-" i) (nth handlers i)))
          (.addLast pipeline "handler" handlers))
        pipeline))))

(defn reconnector [addr]
  (create-handler
   (on-error [^ChannelHandlerContext ctx ^ExceptionEvent e]
             (let [exp (.getCause e)]
               (when (instance? ClosedChannelException exp)
                 (println "channel closed")
                 ;;TODO
                 
                 )))))

(defn- start-tcp-server [port handler encoder decoder boss-pool worker-pool tcp-options]
  (let [factory (NioServerSocketChannelFactory. boss-pool worker-pool)
        bootstrap (ServerBootstrap. factory)
        pipeline (create-pipeline handler encoder decoder)]
    (.setPipelineFactory bootstrap pipeline)
    (.setOptions bootstrap tcp-options)
    (.bind bootstrap (InetSocketAddress. port))))

(defn tcp-server [port handler
                  & {:keys [encoder decoder codec boss-pool worker-pool tcp-options]
                     :or {encoder nil
                          decoder nil
                          codec nil
                          boss-pool (Executors/newCachedThreadPool)
                          worker-pool (Executors/newCachedThreadPool)
                          tcp-options {}}}]
  (let [encoder (or encoder codec)
        decoder (or decoder codec)]
    (start-tcp-server port handler
                      encoder decoder
                      boss-pool worker-pool
                      tcp-options)))

(defn tcp-client [host port handler
                  & {:keys [encoder decoder codec boss-pool worker-pool
                            auto-reconnect tcp-options]
                     :or {encoder nil
                          decoder nil
                          codec nil
                          boss-pool (Executors/newCachedThreadPool)
                          worker-pool (Executors/newCachedThreadPool)
                          auto-reconnect false
                          tcp-options {}}}]
  (let [encoder (or encoder codec)
        decoder (or decoder codec)
        bootstrap (ClientBootstrap.
                   (NioClientSocketChannelFactory. boss-pool worker-pool))
        addr (InetSocketAddress. ^String host ^Integer port)
        chref (atom nil)
        pipeline (create-pipeline (if auto-reconnect
                                    [handler (reconnector bs addr chref)] handler)
                                  encoder decoder)]
    (.setPipelineFactory bootstrap pipeline)
    (.setOptions bootstrap tcp-options)
    (let [ch (.. (.connect bootstrap addr)
                 awaitUninterruptibly
                 getChannel)]
      (reset! chref ch)
      (WrappedSocketChannel. chref))))



