(ns link.pool
  (:import [org.apache.commons.pool PoolableObjectFactory])
  (:import [org.apache.commons.pool.impl GenericObjectPool]))

(defn pool-factory [& {:keys [close-fn open-fn validate-fn]
                       :or {close-fn (fn [_])
                            open-fn (fn [] nil)
                            validate-fn (fn [_] true)}}]
  (reify PoolableObjectFactory
    (makeObject [this]
      (open-fn))
    (destroyObject [this obj]
      (close-fn obj))
    (validateObject [this obj]
      (validate-fn obj))
    (activateObject [this obj]
      )
    (passivateObject [this obj]
      )))

(def exhausted-actions
  {:fail GenericObjectPool/WHEN_EXHAUSTED_FAIL
   :block GenericObjectPool/WHEN_EXHAUSTED_BLOCK
   :grow GenericObjectPool/WHEN_EXHAUSTED_GROW})

(defn create-pool [factory max-active exhausted-action max-wait max-idle]
  (GenericObjectPool. factory
                      max-active
                      (exhausted-actions exhausted-actions)
                      max-wait
                      max-idle))

(defn borrow [pool]
  (.borrowObject pool))

(defn return [pool object]
  (.returnObject pool object))

