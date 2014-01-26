(ns link.tcp
  (:refer-clojure :exclude [send])
  (:use [link.core])
  (:use [link.codec :only [netty-encoder netty-decoder]])
  (:import [java.net InetAddress InetSocketAddress]
           [java.util.concurrent Executors]
           [java.nio.channels ClosedChannelException]
           [javax.net.ssl SSLContext]
           [io.netty.bootstrap Bootstrap ServerBootstrap]
           [io.netty.channel ChannelInitializer Channel
            ChannelHandlerContext ChannelFuture EventLoopGroup]
           [io.netty.channel.nio NioEventLoopGroup]
           [io.netty.channel.socket.nio
            NioServerSocketChannel NioSocketChannel]
           [io.netty.handler.ssl SslHandler]
           [link.core ClientSocketChannel]))

;; handler specs
;; :handler the handler created by create-handler
;; :executor the executor that handler will run on
(defn- channel-init [handler-specs]
  (proxy [ChannelInitializer] []
    (initChannel [this ^Channel ch]
      (let [pipeline (.pipeline ch)]
        (doseq [hs handler-specs]
          (if-not (:executor hs)
            (if (map? hs)
              (.addLast pipeline (:handler hs))
              (.addLast pipeline hs))
            (.addLast pipeline (:executor hs) (:handler hs))))))))

(defn ssl-handler [^SSLContext context client-mode?]
  (SslHandler. (doto (.createSSLEngine context)
                 (.setIssueHandshake true)
		             (.setUseClientMode client-mode?))))

(defn- start-tcp-server [host port handlers encoder decoder
                         tcp-options ssl-context]
  (let [boss-group (NioEventLoopGroup.)
        worker-group (NioEventLoopGroup.)
        bootstrap (ServerBootstrap.)

        handlers (if encoder
                   (conj handlers encoder)
                   handlers)
        handlers (if decoder
                   (conj handlers decoder)
                   handlers)
        handlers (if ssl-context
                   (conj handlers (ssl-handler ssl-handler))
                   handlers)
        channel-initializer (channel-init handlers)

        tcp-options (group-by #(.startsWith (% 0) "child.") (into [] tcp-options))
        parent-tcp-options (get tcp-options false)
        child-tcp-options (map #(vector (subs (% 0) 6) (% 1)) (get tcp-options true))]
    (doto bootstrap
      (.group boss-group worker-group)
      (.channel NioServerSocketChannel)
      (.childHandler channel-initializer))
    (doseq [op parent-tcp-options]
      (.option bootstrap (op 0) (op 1)))
    (doseq [op child-tcp-options]
      (.childOption bootstrap (op 0) (op 1)))

    (.sync (.bind bootstrap (InetAddress/getByName host) port))
    ;; return event loop groups so we can shutdown the server gracefully
    [worker-group boss-group]))

(defn tcp-server [port handler
                  & {:keys [encoder decoder codec threaded?
                            ordered? tcp-options ssl-context
                            host]
                     :or {encoder nil
                          decoder nil
                          codec nil
                          threaded? false
                          ordered? true
                          tcp-options {}
                          ssl-context nil
                          host "0.0.0.0"}}]
  (let [encoder (netty-encoder (or encoder codec))
        decoder (netty-decoder (or decoder codec))]
    (start-tcp-server host
                      port
                      handler
                      encoder
                      decoder
                      tcp-options
                      ssl-context)))

(defn stop-server [event-loop-groups]
  (doseq [^EventLoopGroup elg event-loop-groups]
    (.shutdownGracefully elg)))

(defn tcp-client-factory [handlers
                          & {:keys [encoder decoder codec tcp-options]
                             :or {tcp-options {}}}]
  (let [worker-group (NioEventLoopGroup.)
        encoder (netty-encoder (or encoder codec))
        decoder (netty-decoder (or decoder codec))
        bootstrap (Bootstrap.)
        handlers (if encoder (conj handlers encoder) handlers)
        handlers (if encoder (conj handlers decoder) handlers)
        channel-initializer (channel-init handlers)
        tcp-options (into [] tcp-options)]

    (doto bootstrap
      (.group worker-group)
      (.channel NioSocketChannel)
      (.handler channel-initializer))
    (doseq [op tcp-options]
      (.option bootstrap (op 0) (op 1)))

    [bootstrap worker-group]))

(defn stop-clients [client-factory]
  (let [^EventLoopGroup elg (client-factory 1)]
    (.shutdownGracefully elg)))

(defn- connect [^Bootstrap bootstrap addr]
  (loop [chf (.. (.connect bootstrap addr) (.sync))
         interval 5000]
    (if (.isSuccess ^ChannelFuture chf)
      (.channel ^ChannelFuture chf)
      (do
        (Thread/sleep interval)
        (recur (.. (.connect bootstrap addr) (.sync)) interval)))))

(defn tcp-client [^Bootstrap bootstrap host port
                  & {:keys [lazy-connect]
                     :or {lazy-connect false}}]
  (let [addr (InetSocketAddress. ^String host ^Integer port)]
    (let [connect-fn (fn [] (connect bootstrap addr))
          chref (agent (if-not lazy-connect (connect-fn)))]
      (ClientSocketChannel. chref connect-fn))))
