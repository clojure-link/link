(ns link.pool
  (:import [org.apache.commons.pool PoolableObjectFactory])
  (:import [org.apache.commons.pool.impl GenericObjectPool]))

(defprotocol Pool
  (borrow [this])
  (return [this obj]))

(extend-type GenericObjectPool
  Pool
  (borrow [this]
    (.borrowObject this))
  (return [this obj]
    (.returnObject this obj)))

(def exhausted-policy-mapping
  {:block GenericObjectPool/WHEN_EXHAUSTED_BLOCK
   :fail GenericObjectPool/WHEN_EXHAUSTED_FAIL
   :grow GenericObjectPool/WHEN_EXHAUSTED_GROW})

(defmacro pool [options & factory-specs]
  (let [default-factory-specs
        {'makeObject (list 'makeObject '[this] 'nil)
         'destroyObject (list 'destroyObject '[this obj])
         'validateObject (list 'validateObject '[this obj] 'true)
         'activateObject (list 'activateObject '[this obj])
         'passivateObject (list 'passivateObject '[this obj])}
        
        provided-factory-specs
        (into {} (map #(vector (first %) %) factory-specs))
        
        factory-specs
        (map #(get provided-factory-specs % (default-factory-specs %))
             (keys default-factory-specs))]
    `(let [factory# (reify PoolableObjectFactory ~@factory-specs)]
       (GenericObjectPool. factory#
                           (get ~options :max-active 8)
                           (exhausted-policy-mapping
                            (get ~options :exhausted-action :block))
                           (get ~options :max-wait -1)
                           (get ~options :test-on-borrow false)
                           (get ~options :test-on-return false)))))




