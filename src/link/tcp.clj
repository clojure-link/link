(ns link.tcp
  (:use [link.codec :only [netty-encoder netty-decoder]])
  (:use [link.pool :only [pool-factory create-pool]])
  (:import [java.net InetSocketAddress])
  (:import [java.util.concurrent Executors])
  (:import [org.jboss.netty.bootstrap
            ClientBootstrap
            ServerBootstrap])
  (:import [org.jboss.netty.channel
            Channels
            ChannelPipelineFactory])
  (:import [org.jboss.netty.channel.socket.nio
            NioServerSocketChannelFactory
            NioClientSocketChannelFactory]))

(defn- create-pipeline [handler encoder decoder]
  (reify ChannelPipelineFactory
    (getPipeline [this]
      (let [pipeline (Channels/pipeline)]
        (when-not (nil? decoder)
          (.addLast pipeline "decoder" (netty-decoder decoder)))
        (when-not (nil? encoder)
          (.addLast pipeline "encoder" (netty-encoder encoder)))
        (.addLast pipeline "handler" handler)
        pipeline))))

(defn- start-tcp-server [port handler encoder decoder boss-pool worker-pool]
  (let [factory (NioServerSocketChannelFactory. boss-pool worker-pool)
        bootstrap (ServerBootstrap. factory)
        pipeline (create-pipeline handler encoder decoder)]
    (.setPipelineFactory bootstrap pipeline)
    (.bind bootstrap (InetSocketAddress. port))))

(defn tcp-server [port handler
                  & {:keys [encoder decoder codec boss-pool worker-pool]
                     :or {encoder nil
                          decoder nil
                          codec nil
                          boss-pool (Executors/newCachedThreadPool)
                          worker-pool (Executors/newCachedThreadPool)}}]
  (let [encoder (or encoder codec)
        decoder (or decoder codec)]
    (start-tcp-server port handler encoder decoder boss-pool worker-pool)))

(defn tcp-client [host port handler
                  & {:keys [encoder decoder codec boss-pool worker-pool]
                     :or {encoder nil
                          decoder nil
                          codec nil
                          boss-pool (Executors/newCachedThreadPool)
                          worker-pool (Executors/newCachedThreadPool)}}]
  (let [encoder (or encoder codec)
        decoder (or decoder codec)
        bootstrap (ClientBootstrap.
                   (NioClientSocketChannelFactory. boss-pool worker-pool))
        pipeline (create-pipeline handler encoder decoder)]
    (.setPipelineFactory bootstrap pipeline)
    (.. (.connect bootstrap (InetSocketAddress. host port))
        awaitUninterruptibly
        getChannel)))

(defn tcp-client-pool [host port handler
                       & {:keys [encoder decoder codec boss-pool worker-pool]
                          :or {encoder nil
                               decoder nil
                               codec nil
                               boss-pool (Executors/newCachedThreadPool)
                               worker-pool (Executors/newCachedThreadPool)
                               max-active 8
                               exhausted-action :block
                               max-wait -1
                               max-idle 8}}]
  (let [open-fn (fn [] (apply tcp-client host port handler options))
        close-fn (fn [c] (.close c))
        validate-fn (fn [c] (.isOpen c))
        factory (pool-factory :close-fn close-fn
                              :open-fn open-fn
                              :validate-fn validate-fn)]
    (create-pool factory max-active exhausted-action max-wait max-idle)))

