(ns link.threads
  (:import [java.util.concurrent ThreadFactory]
           [io.netty.util.concurrent DefaultEventExecutorGroup]))

(defn new-executor
  ([threads] (DefaultEventExecutorGroup. threads))
  ([threads thread-factory] (DefaultEventExecutorGroup. threads thread-factory)))

(defn ^ThreadFactory prefix-thread-factory [name-prefix]
  (let [counter (atom 0)]
    (reify ThreadFactory
      (newThread [this r]
        (doto (Thread. r)
          (.setName (str name-prefix "-" (swap! counter inc))))))))
