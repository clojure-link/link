(ns link.tcp
  (:refer-clojure :exclude [send])
  (:use [link.core])
  (:use [link.codec :only [netty-encoder netty-decoder]])
  (:use [link.pool :only [pool]])
  (:import [java.net InetAddress]
           [java.util.concurrent Executors]
           [java.nio.channels ClosedChannelException]
           [javax.net.ssl SSLContext]
           [io.netty.bootstrap ClientBootstrap ServerBootstrap]
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

    (.sync (.bind bootstrap (InetAddress. host) port))
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
  (doseq [^EventLoopGroup el event-loop-groups]
    (.shutdownGracefully el)))

(defn tcp-client-factory [handler
                          & {:keys [encoder decoder codec tcp-options]
                             :or {tcp-options {}}}]
  (let [encoder (netty-encoder (or encoder codec))
        decoder (netty-decoder (or decoder codec))
        bootstrap (ClientBootstrap.
                   (NioClientSocketChannelFactory.
                    (Executors/newCachedThreadPool)
                    (Executors/newCachedThreadPool)))
        handlers [encoder decoder handler]
        pipeline (apply create-pipeline handlers)]
    (.setPipelineFactory bootstrap pipeline)
    (.setOptions bootstrap tcp-options)
    bootstrap))

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

(defn tcp-client [^ClientBootstrap bootstrap host port
                  & {:keys [lazy-connect]
                     :or {lazy-connect false}}]
  (let [addr (InetSocketAddress. ^String host ^Integer port)]
    (let [connect-fn (fn [] (connect bootstrap addr))
          chref (agent (if-not lazy-connect (connect-fn)))]
      (ClientSocketChannel. chref connect-fn))))

(defn tcp-client-pool [^ClientBootstrap bootstrap host port
                       & {:keys [lazy-connect pool-options]
                          :or {lazy-connect false
                               pool-options {:max-active 8
                                             :exhausted-policy :block
                                             :max-wait -1}}}]
  (let [addr (InetSocketAddress. ^String host ^Integer port)
        maker (fn []
                (let [conn-fn (fn [] (connect bootstrap addr))]
                  (ClientSocketChannel.
                   (agent (if-not lazy-connect (conn-fn)))
                   conn-fn)))]
    (pool pool-options
          (makeObject [this] (maker))
          (destroyObject [this client] (close client))
          (validateObject [this client] (valid? client)))))
