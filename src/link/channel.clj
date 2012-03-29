(ns link.channel
  (:refer-clojure :exclude [send]))

(defprotocol NettyChannel
  (send [this msg])
  (close [this]))


