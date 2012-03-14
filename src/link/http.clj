(ns link.http
  (:use [link.core])
  (:import [java.net InetSocketAddress])
  (:import [java.util.concurrent Executors])
  (:import [org.jboss.netty.channel
            Channels
            ChannelPipelineFactory])
  (:import [org.jboss.netty.bootstrap ServerBootstrap])
  (:import [org.jboss.netty.channel.socket.nio
            NioServerSocketChannelFactory])
  (:import [org.jboss.netty.handler.codec.http
            HttpRequestDecoder
            HttpResponseEncoder]))

(defn create-http-pipeline [handler]
  (reify ChannelPipelineFactory
    (getPipeline [this]
      (let [pipeline (Channels/pipeline)]
        (.addLast pipeline "decoder" (HttpRequestDecoder.))
        (.addLast pipeline "encoder" (HttpResponseEncoder.))
        (.addLast pipeline "handler" handler)))))

(defn create-http-handler-from-ring [ring-fn]
  (create-handler
   (on-message [ctx e]
               ;;TODO
               )))

(defn http-server [port ring-fn
                   & {:keys [boss-pool worker-pool]
                      :or {boss-pool (Executors/newCachedThreadPool)
                           worker-pool (Executors/newCachedThreadPool)}}]
  (let [factory (NioServerSocketChannelFactory. boss-pool worker-pool)
        bootstrap (ServerBootstrap. factory)
        pipeline (create-http-pipeline
                  (create-http-handler-from-ring ring-fn))]
    (.setPipelineFactory bootstrap pipeline)
    (.bind bootstrap (InetSocketAddress. port))))

