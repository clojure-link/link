(ns link.udp
  (:require [link.core :refer :all]
            [link.codec :refer [netty-encoder netty-decoder]]
            [clojure.tools.logging :as logging])
  (:import [java.net InetAddress InetSocketAddress]
           [java.util List]
           [io.netty.bootstrap Bootstrap]
           [io.netty.buffer ByteBuf]
           [io.netty.channel.nio NioEventLoopGroup]
           [io.netty.handler.codec DatagramPacketDecoder MessageToMessageDecoder]
           [io.netty.channel ChannelInitializer Channel ChannelHandler ChannelFuture
            ChannelPipeline ChannelOption]
           [io.netty.channel.socket.nio NioDatagramChannel]
           [io.netty.util.concurrent EventExecutorGroup]
           [link.core ClientSocketChannel]))


(defn- append-single-handler->pipeline
  ([^ChannelPipeline pipeline ^String name ^ChannelHandler h]
   (.addLast pipeline name h)))

(defn- append-handlers->pipeline
  ([^ChannelPipeline pipeline handlers]
    (prn handlers)
   (.addLast pipeline (into-array ChannelHandler handlers))))

(defn channel-init [handlers]
  (proxy [ChannelInitializer] []
    (initChannel [^NioDatagramChannel ch]
      (let [pipeline ^ChannelPipeline (.pipeline ch)]
        (append-single-handler->pipeline pipeline "udp-decoder"
          (DatagramPacketDecoder.
            (proxy [MessageToMessageDecoder] []
              (decode [ctx ^ByteBuf buf ^List out]
                (.add out buf)))))
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
