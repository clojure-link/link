(ns link.udp
  (:require [link.core :refer :all]
            [link.codec :refer [netty-encoder netty-decoder]]
            [clojure.tools.logging :as logging])
  (:import [java.net InetAddress InetSocketAddress]
           [io.netty.bootstrap Bootstrap]
           [io.netty.channel ChannelOption]
           [io.netty.channel.socket.nio NioDatagramChannel]
           [link.core ClientSocketChannel]))

(defn channel-init [handler-specs]
  (proxy [ChannelInitializer] []
    (initChannel [^NioDatagramChannel ch]
      (let [pipeline ^ChannelPipeline (.pipeline ch)
            ;; the handler-spec itself can be a factory function that
            ;; returns handler-specs
            handler-specs (if (fn? handler-specs)
                            (handler-specs ch)
                            handler-specs)]
        (doseq [hs handler-specs]
          (let [hs (if (map? hs) hs {:handler hs})
                h (if (fn? (:handler hs)) ((:handler hs) ch) (:handler hs))]
            (cond
              (and (:executor hs) (:name hs))
              (append-single-handler->pipeline pipeline (:executor hs) (:name hs) h)

              (:name hs)
              (append-single-handler->pipeline pipeline (:name hs) h)

              (:executor hs)
              (append-handlers->pipeline pipeline (:executor hs) [h])

              :else
              (append-handlers->pipeline pipeline [h]))))))))

(defn- start-udp-server [host port handlers options]
  (let [worker-group (or (:worker-group options) (NioEventLoopGroup.))
        bootstrap (or (:bootstrap options) (Bootstrap.))
        handlers (cond
                   (fn? handlers) handlers
                   (sequential? handlers) handlers
                   :else [handlers])
        channel-initializer (channel-init handlers)
        options (into [] options)]

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
  (let [handlers (cond
                   (fn? handlers) handlers
                   (sequential? handlers) handlers
                   :else [handlers])]
    (start-udp-server host
                      port
                      handlers
                      options)))
