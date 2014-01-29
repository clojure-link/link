(ns link.threads
  (:import [io.netty.util.concurrent DefaultEventExecutorGroup]))

(defn new-executor [threads]
  (DefaultEventExecutorGroup. threads))
