(ns link.threads
  (:import [io.netty.util.concurrent DefaultEventExecutor]))

(defn new-executor [threads]
  (DefaultEventExecutor. threads))
