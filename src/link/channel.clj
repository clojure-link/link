(ns link.channel
  (:refer-clojure :exclude [send])
  (:import [java.net InetSocketAddress])
  (:import [org.jboss.netty.channel
            Channel]))

(defprotocol SocketChannel
  (send [this msg])
  (close [this]))

(deftype WrappedSocketChannel [ch-ref]
  ClientChannelProtocol
  (send [this msg]
    (.write ^Channel @ch msg))
  (close [this]
    (.close ^Channel @ch)))


