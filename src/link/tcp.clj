(ns link.tcp
  (:use [link.core])
  (:use [link.codec :only [netty-encoder netty-decoder]])
  (:require [clojure.tools.logging :as logging])
  (:import [java.net InetAddress InetSocketAddress]
           [io.netty.bootstrap Bootstrap ServerBootstrap]
           [io.netty.channel ChannelInitializer Channel ChannelHandler
            ChannelHandlerContext ChannelFuture EventLoopGroup
            ChannelPipeline ChannelOption]
           [io.netty.channel.nio NioEventLoopGroup]
           [io.netty.channel.socket.nio
            NioServerSocketChannel NioSocketChannel]
           [io.netty.handler.ssl SslHandler SniHandler
            SslContext]
           [io.netty.util DomainNameMapping]
           [io.netty.util.concurrent EventExecutorGroup]
           [link.core ClientSocketChannel]))

;; handler specs
;; :handler the handler created by create-handler or a factory
;; function for stateful handler
;; :executor the executor that handler will run on
(defn- channel-init [handler-specs]
  (proxy [ChannelInitializer] []
    (initChannel [^Channel ch]
      (let [pipeline ^ChannelPipeline (.pipeline ch)]
        (doseq [hs handler-specs]
          (if (map? hs)
            (let [h (if (fn? (:handler hs)) ((:handler hs) ch) (:handler hs))]
              (if-not (:executor hs)
                (.addLast pipeline ^"[Lio.netty.channel.ChannelHandler;" (into-array ChannelHandler [h]))
                (.addLast pipeline
                          ^EventExecutorGroup (:executor hs)
                          ^"[Lio.netty.channel.ChannelHandler;" (into-array ChannelHandler [h]))))
            (let [h (if (fn? hs) (hs ch) hs)]
              (.addLast pipeline ^"[Lio.netty.channel.ChannelHandler;" (into-array ChannelHandler [h])))))))))

(defn ssl-handler [^SslContext context]
  (fn [^Channel ch] (.newHandler context (.alloc ch))))

(defn sni-ssl-handler [context-map ^SslContext default-context]
  (let [ddm (DomainNameMapping. default-context)]
    (doseq [[k v] context-map]
      (.add ddm ^String k ^SslContext v))
    (fn [_] (SniHandler. ddm))))

(defn- start-tcp-server [host port handlers encoder decoder
                         options ssl-context ssl-contexts-map]
  (let [boss-group (NioEventLoopGroup.)
        worker-group (NioEventLoopGroup.)
        bootstrap (ServerBootstrap.)

        handlers (if encoder
                   (conj (seq handlers) encoder)
                   handlers)
        handlers (if decoder
                   (conj (seq handlers) decoder)
                   handlers)
        handlers (cond
                  ssl-contexts-map (conj (seq handlers) (sni-ssl-handler ssl-contexts-map ssl-context))
                  ssl-context (conj (seq handlers) (ssl-handler ssl-context false))
                  :else handlers)

        channel-initializer (channel-init handlers)

        options (group-by #(.startsWith (name (% 0)) "child.") (into [] options))
        parent-options (get options false)
        child-options (map #(vector (keyword (subs (name (% 0)) 6)) (% 1)) (get options true))]
    (doto bootstrap
      (.group boss-group worker-group)
      (.channel NioServerSocketChannel)
      (.childHandler channel-initializer))
    (doseq [op parent-options]
      (.option bootstrap (channel-option (op 0)) (op 1)))
    (doseq [op child-options]
      (.childOption bootstrap (channel-option (op 0)) (op 1)))

    (.sync ^ChannelFuture (.bind bootstrap (InetAddress/getByName host) port))
    ;; return event loop groups so we can shutdown the server gracefully
    [worker-group boss-group]))

(defn tcp-server [port handlers
                  & {:keys [encoder decoder codec
                            options ssl-context ssl-contexts-map
                            host]
                     :or {encoder nil
                          decoder nil
                          codec nil
                          options {}
                          ssl-context nil
                          host "0.0.0.0"}}]
  (let [handlers (if (vector? handlers) handlers [handlers])
        encoder (netty-encoder (or encoder codec))
        decoder (netty-decoder (or decoder codec))]
    (start-tcp-server host
                      port
                      handlers
                      encoder
                      decoder
                      options
                      ssl-context
                      ssl-contexts-map)))

(defn stop-server [event-loop-groups]
  (doseq [^EventLoopGroup elg event-loop-groups]
    (.sync (.shutdownGracefully elg))))

(defn tcp-client-factory [handlers
                          & {:keys [encoder decoder codec options ssl-context]
                             :or {options {}}}]
  (let [worker-group (NioEventLoopGroup.)
        encoder (netty-encoder (or encoder codec))
        decoder (netty-decoder (or decoder codec))
        bootstrap (Bootstrap.)
        handlers (if (vector? handlers) handlers [handlers])
        handlers (if encoder (conj (seq handlers) encoder) handlers)
        handlers (if decoder (conj (seq handlers) decoder) handlers)
        handlers (if ssl-context
                   (conj (seq handlers) #(ssl-handler ssl-context true))
                   handlers)

        channel-initializer (channel-init handlers)
        options (into [] options)]

    (doto bootstrap
      (.group worker-group)
      (.channel NioSocketChannel)
      (.handler channel-initializer))
    (doseq [op options]
      (.option bootstrap (channel-option (op 0)) (op 1)))

    [bootstrap worker-group]))

(defn stop-clients [client-factory]
  (let [^EventLoopGroup elg (client-factory 1)]
    (.sync (.shutdownGracefully elg))))

(defn- connect [^Bootstrap bootstrap addr]
  (loop [interval 1000]
    (let [chf (.await ^ChannelFuture
                      (.connect bootstrap addr))]
      (if (and chf (.isSuccess ^ChannelFuture chf))
        (.channel ^ChannelFuture chf)
        (do
          (logging/infof "Trying to connect to %s %dms later."
                         (str addr) (* 2 interval))
          (Thread/sleep interval)
          (recur (* 2 interval)))))))

(defn tcp-client [factory host port
                  & {:keys [lazy-connect]
                     :or {lazy-connect false}}]
  (let [bootstrap (factory 0)
        addr (InetSocketAddress. ^String host ^Integer port)]
    (let [connect-fn (fn [] (connect bootstrap addr))
          chref (agent (if-not lazy-connect (connect-fn)))]
      (ClientSocketChannel. chref connect-fn))))
