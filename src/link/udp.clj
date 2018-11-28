(ns link.udp
  (:require [link.core :refer :all]
            [link.codec :refer [netty-encoder netty-decoder]]
            [clojure.tools.logging :as logging])
  (:import [java.util List]
           [java.net InetAddress InetSocketAddress]
           [io.netty.bootstrap Bootstrap]
           [io.netty.buffer ByteBuf]
           [io.netty.channel.socket DatagramPacket]
           [io.netty.channel.nio NioEventLoopGroup]
           [io.netty.handler.codec MessageToMessageCodec]
           [io.netty.channel Channel
            ChannelInitializer ChannelHandler ChannelFuture
            ChannelPipeline ChannelOption ChannelHandlerContext]
           [io.netty.channel.socket.nio NioDatagramChannel]
           [io.netty.util.concurrent GenericFutureListener]))

(extend-protocol LinkMessageChannel
  NioDatagramChannel
  (id [this]
    (channel-id this))
  (short-id [this]
    (short-channel-id this))
  (send! [this msg]
    (.writeAndFlush this msg (.voidPromise this)))
  (send!* [this msg cb]
    (if cb
      (let [cf (.writeAndFlush this msg)]
        (.addListener ^ChannelFuture cf (reify GenericFutureListener
                                          (operationComplete [this f] (cb f)))))
      (.writeAndFlush this msg (.voidPromise this))))
  (channel-addr [this]
    (.localAddress this))
  (remote-addr [this] nil)
  (close! [this]
    (.close this))
  (valid? [this]
    (.isActive this)))

(defn- append-single-handler->pipeline
  ([^ChannelPipeline pipeline ^String name ^ChannelHandler h]
   (.addLast pipeline name h)))

(defn- append-handlers->pipeline
  ([^ChannelPipeline pipeline handlers]
   (.addLast pipeline (into-array ChannelHandler handlers))))

(defn channel-init [handlers]
  (proxy [ChannelInitializer] []
    (initChannel [^NioDatagramChannel ch]
      (let [pipeline ^ChannelPipeline (.pipeline ch)]
        (append-single-handler->pipeline pipeline "udp-codec"
          (let [channels (atom #{})]
            (proxy [MessageToMessageCodec] []
              (decode [^ChannelHandlerContext ctx ^DatagramPacket buf ^List out]
                ;(swap! channels #(conj % (.sender buf)))
                (reset! channels #{(.sender buf)})
                (.add out (.copy (.content buf))))
              (encode [^ChannelHandlerContext ctx ^ByteBuf msg ^List out]
                (doseq [s @channels]
                (.add out (DatagramPacket. (.copy msg) s)))))))
        (doseq [h handlers]
          (let [h (if (fn? h) (h ch) h)]
          (append-handlers->pipeline pipeline [h])))))))

(defn client-channel-init [handlers host port]
  (proxy [ChannelInitializer] []
    (initChannel [^NioDatagramChannel ch]
      (let [pipeline ^ChannelPipeline (.pipeline ch)]
        (append-single-handler->pipeline pipeline "udp-codec"
          (let [dst (InetSocketAddress. host, port)]
            (proxy [MessageToMessageCodec] []
              (decode [^ChannelHandlerContext ctx ^DatagramPacket buf ^List out]
                (.add out (.copy (.content buf))))
              (encode [^ChannelHandlerContext ctx ^ByteBuf msg ^List out]
                (.add out (DatagramPacket. (.copy msg) dst))))))
        (doseq [h handlers]
          (let [h (if (fn? h) (h ch) h)]
          (append-handlers->pipeline pipeline [h])))))))

(defn- start-udp-server [host port handlers options]
  (let [worker-group (or (:worker-group options) (NioEventLoopGroup.))
        bootstrap (or (:bootstrap options) (Bootstrap.))
        channel-initializer (channel-init handlers)]

        (doto bootstrap
          (.group worker-group)
          (.channel NioDatagramChannel)
          (.option (ChannelOption/SO_BROADCAST) true)
          (.handler channel-initializer))

      (.sync ^ChannelFuture (.bind bootstrap (InetAddress/getByName host) port))
      [bootstrap worker-group]))

(defn udp-server [port handlers
                  & {:keys [options host]
                     :or {options {}
                          host "0.0.0.0"}}]
    (start-udp-server host port handlers options))

(defn stop-server [[bootstrap worker-group]]
    (.sync (.shutdownGracefully ^NioEventLoopGroup worker-group)))

(defn udp-client [host port handlers]
  (let [worker-group (NioEventLoopGroup.)
        bootstrap (Bootstrap.)
        channel-initializer (client-channel-init handlers host port)]

        (doto bootstrap
          (.group worker-group)
          (.channel NioDatagramChannel)
          (.option (ChannelOption/SO_BROADCAST) true)
          (.handler channel-initializer))

      (.channel (.sync ^ChannelFuture (.bind bootstrap 0)))
      #_[bootstrap worker-group]))

(defn stop-client [^Channel udp-ch]
  (.close udp-ch))
