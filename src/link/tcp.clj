(ns link.tcp
  (:require [link.core :refer :all]
            [link.codec :refer [netty-encoder netty-decoder]]
            [clojure.tools.logging :as logging])
  (:import [java.net InetAddress InetSocketAddress]
           [io.netty.bootstrap Bootstrap ServerBootstrap]
           [io.netty.channel ChannelInitializer Channel ChannelHandler
            ChannelFuture EventLoopGroup
            ChannelPipeline ChannelOption]
           [io.netty.channel.nio NioEventLoopGroup]
           [io.netty.channel.socket.nio
            NioServerSocketChannel NioSocketChannel]
           [io.netty.util.concurrent EventExecutorGroup]
           [link.core ClientSocketChannel]))

(defn to-channel-option
  ([co]
   (to-channel-option co nil))
  ([co class]
   (let [co (name co)
         co (-> (if (.startsWith co "child.")
                  (subs co 6) co)
                (clojure.string/replace #"-" "_")
                (clojure.string/upper-case))]
     (if class
       (ChannelOption/valueOf ^Class class co)
       (ChannelOption/valueOf co)))))

(defn- append-single-handler->pipeline
  ([^ChannelPipeline pipeline ^String name ^ChannelHandler h]
   (.addLast pipeline name h))
  ([^ChannelPipeline pipeline ^EventExecutorGroup executor ^String name ^ChannelHandler h]
   (.addLast pipeline executor name h)))

(defn- append-handlers->pipeline
  ([^ChannelPipeline pipeline handlers]
   (.addLast pipeline ^"[Lio.netty.channel.ChannelHandler;" (into-array ChannelHandler handlers)))
  ([^ChannelPipeline pipeline ^EventExecutorGroup executor handlers]
   (.addLast pipeline executor ^"[Lio.netty.channel.ChannelHandler;" (into-array ChannelHandler handlers))))

;; handler specs
;; :handler the handler created by create-handler or a factory
;; function for stateful handler
;; :executor the executor that handler will run on
(defn channel-init [handler-specs]
  (proxy [ChannelInitializer] []
    (initChannel [^Channel ch]
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

(defn- start-tcp-server [host port handlers options]
  (let [boss-group (or (:boss-group options) (NioEventLoopGroup.))
        worker-group (or (:worker-group options) (NioEventLoopGroup.))
        bootstrap (or (:bootstrap options) (ServerBootstrap.))

        channel-initializer (channel-init handlers)

        options (group-by #(.startsWith (name (% 0)) "child.") (into [] options))
        parent-options (get options false)
        child-options (map #(vector (keyword (subs (name (% 0)) 6)) (% 1)) (get options true))]
    (doto bootstrap
      (.group boss-group worker-group)
      (.channel NioServerSocketChannel)
      (.childHandler channel-initializer))
    (doseq [op parent-options]
      (let [op (flatten op)]
        (.option bootstrap (apply to-channel-option (butlast op)) (last op))))
    (doseq [op child-options]
      (let [op (flatten op)]
        (.childOption bootstrap (apply to-channel-option (butlast op)) (last op))))

    (.sync ^ChannelFuture (.bind bootstrap (InetAddress/getByName host) port))
    ;; return event loop groups so we can shutdown the server gracefully
    [worker-group boss-group]))

(defn tcp-server [port handlers
                  & {:keys [options host]
                     :or {options {}
                          host "0.0.0.0"}}]
  (let [handlers (cond
                   (fn? handlers) handlers
                   (sequential? handlers) handlers
                   :else [handlers])]
    (start-tcp-server host
                      port
                      handlers
                      options)))

(defn stop-server [event-loop-groups]
  (doseq [^EventLoopGroup elg event-loop-groups]
    (.sync (.shutdownGracefully elg))))

(defn server-bootstrap
  "Allow multiple server instance share the same eventloop:
  Just use the result of this function as option in `tcp-server`"
  []
  {:boss-group (NioEventLoopGroup.)
   :worker-group (NioEventLoopGroup.)
   :boostrap (ServerBootstrap.)})

(defn tcp-client-factory [handlers
                          & {:keys [options]
                             :or {options {}}}]
  (let [worker-group (NioEventLoopGroup.)
        bootstrap (Bootstrap.)
        handlers (cond
                   (fn? handlers) handlers
                   (sequential? handlers) handlers
                   :else [handlers])

        channel-initializer (channel-init handlers)
        options (into [] options)]

    (doto bootstrap
      (.group worker-group)
      (.channel NioSocketChannel)
      (.handler channel-initializer))
    (doseq [op (into [] options)]
      (let [op (flatten op)]
        (.option bootstrap (apply to-channel-option (butlast op)) (last op))))

    [bootstrap worker-group]))

(defn stop-clients [client-factory]
  (let [^EventLoopGroup elg (client-factory 1)]
    (.sync (.shutdownGracefully elg))))

(defn- connect [^Bootstrap bootstrap addr stopped]
  (loop [interval 1000]
    (when-not @stopped
      (let [chf (.await ^ChannelFuture
                        (.connect bootstrap addr))]
        (if (and chf (.isSuccess ^ChannelFuture chf))
          (.channel ^ChannelFuture chf)
          (do
            (logging/infof "Trying to connect to %s %dms later."
                           (str addr) (* 2 interval))
            (Thread/sleep interval)
            (recur (* 2 interval))))))))

(defn tcp-client [factory host port
                  & {:keys [lazy-connect]
                     :or {lazy-connect false}}]
  (let [bootstrap (factory 0)
        addr (InetSocketAddress. ^String host ^Integer port)
        stopped (atom false)]
    (let [connect-fn (fn [] (connect bootstrap addr stopped))
          chref (agent (when-not lazy-connect (connect-fn)))]
      (ClientSocketChannel. chref connect-fn stopped))))
