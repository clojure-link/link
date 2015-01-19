(ns link.ssl
  (:import [io.netty.handler.ssl SslHandler SniHandler SslContext]
           [io.netty.channel Channel]
           [io.netty.util DomainNameMapping]))

(defn ssl-handler [^SslContext context]
  (fn [^Channel ch] (.newHandler context (.alloc ch))))

(defn sni-ssl-handler [context-map ^SslContext default-context]
  (let [ddm (DomainNameMapping. default-context)]
    (doseq [[k v] context-map]
      (.add ddm ^String k ^SslContext v))
    (fn [_] (SniHandler. ddm))))
