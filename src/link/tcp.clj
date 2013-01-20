(ns link.tcp
  (:refer-clojure :exclude [send])
  (:use [link.core])
  (:use [link.codec :only [netty-encoder netty-decoder]])
  (:import [java.net InetSocketAddress]
           [java.util.concurrent Executors]
           [java.nio.channels ClosedChannelException]
           [javax.net.ssl SSLContext]
           [org.jboss.netty.bootstrap ClientBootstrap ServerBootstrap]
           [org.jboss.netty.channel Channels ChannelPipelineFactory Channel
            ChannelHandlerContext ChannelFuture]
           [org.jboss.netty.channel.socket.nio
            NioServerSocketChannelFactory NioClientSocketChannelFactory]
           [org.jboss.netty.handler.ssl SslHandler]
           [link.core ClientSocketChannel]))

(defn- create-pipeline [& handlers]
  (reify ChannelPipelineFactory
    (getPipeline [this]
      (let [pipeline (Channels/pipeline)]
        (doseq [i (range (count handlers))]
          (.addLast pipeline (str "handler-" i) (nth handlers i)))
        pipeline))))

(defn get-ssl-handler [context client-mode?]
  (SslHandler. (doto (.createSSLEngine context)
                 (.setIssueHandshake true)
		 (.setUseClientMode client-mode?))))

(defn- start-tcp-server [port handler encoder decoder threaded?
                         ordered tcp-options ssl-context]
  (let [factory (NioServerSocketChannelFactory.
                 (Executors/newCachedThreadPool)
                 (Executors/newCachedThreadPool))
        bootstrap (ServerBootstrap. factory)
        handlers* (if-not threaded?
                    [encoder decoder handler]
                    [encoder decoder (threaded-handler ordered)
                     handler])
        handlers (if ssl-context
                   (concat [(get-ssl-handler ssl-context false)]
                           handlers*)
                   handlers*)
        pipeline (apply create-pipeline handlers)]
    (.setPipelineFactory bootstrap pipeline)
    (.setOptions bootstrap tcp-options)
    (link.core.Server. bootstrap (.bind bootstrap (InetSocketAddress. port)))))

(defn tcp-server [port handler
                  & {:keys [encoder decoder codec threaded?
                            ordered? tcp-options ssl-context]
                     :or {encoder nil
                          decoder nil
                          codec nil
                          threaded? false
                          ordered? true
                          tcp-options {}
                          ssl-context nil}}]
  (let [encoder (netty-encoder (or encoder codec))
        decoder (netty-decoder (or decoder codec))]
    (start-tcp-server port handler
                      encoder decoder
                      threaded?
                      ordered?
                      tcp-options
                      ssl-context)))

(defn- connect [^ClientBootstrap bootstrap addr]
  (loop [chf (.. (.connect bootstrap addr)
                 awaitUninterruptibly)
         interval 5000]
    (if (.isSuccess ^ChannelFuture chf)
      (.getChannel ^ChannelFuture chf)
      (do
        (Thread/sleep interval)
        (recur (.. (.connect bootstrap addr)
                   awaitUninterruptibly)
               interval)))))

(defn tcp-client [host port handler
                  & {:keys [encoder decoder codec tcp-options ssl-context]
                     :or {encoder nil
                          decoder nil
                          codec nil
                          tcp-options {}
                          ssl-context nil}}]
  (let [encoder (netty-encoder (or encoder codec))
        decoder (netty-decoder (or decoder codec))
        bootstrap (ClientBootstrap.
                   (NioClientSocketChannelFactory.
                    (Executors/newCachedThreadPool)
                    (Executors/newCachedThreadPool)))
        addr (InetSocketAddress. ^String host ^Integer port)
        handlers* [encoder decoder handler]
        handlers (if ssl-context
                   (concat [(get-ssl-handler ssl-context true)] handlers*)
                   handlers*)
        pipeline (apply create-pipeline handlers)]
    (.setPipelineFactory bootstrap pipeline)
    (.setOptions bootstrap tcp-options)
    (let [connect-fn (fn [] (connect bootstrap addr))
          chref (agent (connect-fn))]
      (ClientSocketChannel. chref connect-fn))))

