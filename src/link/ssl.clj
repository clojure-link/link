(ns link.ssl
  (:import [io.netty.handler.ssl SslHandler SniHandler SslContext SslProvider
            SslContextBuilder SupportedCipherSuiteFilter
            ApplicationProtocolConfig
            ApplicationProtocolConfig$Protocol
            ApplicationProtocolConfig$SelectedListenerFailureBehavior
            ApplicationProtocolConfig$SelectorFailureBehavior
            ApplicationProtocolNames]
           [io.netty.channel Channel]
           [io.netty.util DomainNameMapping]
           [io.netty.handler.codec.http2 Http2SecurityUtil]
           [javax.net.ssl SSLContext]))

(defn ssl-handler-from-jdk-ssl-context [^SSLContext ctx client?]
  (fn [_] (SslHandler. (doto (.createSSLEngine ctx)
                        (.setUseClientMode client?)))))

(defn ssl-handler [^SslContext context]
  (fn [^Channel ch] (.newHandler context (.alloc ch))))

(defn sni-ssl-handler [context-map ^SslContext default-context]
  (let [ddm (DomainNameMapping. default-context)]
    (doseq [[k v] context-map]
      (.add ddm ^String k ^SslContext v))
    (fn [_] (SniHandler. ddm))))

(defn ssl-context-for-http2 [cert key provider]
  (let [backend (case provider
                  :jdk SslProvider/JDK
                  :openssl SslProvider/OPENSSL)]
    (.. (SslContextBuilder/forServer cert key)
        (sslProvider backend)
        (ciphers Http2SecurityUtil/CIPHERS SupportedCipherSuiteFilter/INSTANCE)
        (applicationProtocolConfig
         (ApplicationProtocolConfig.
          ApplicationProtocolConfig$Protocol/ALPN
          ApplicationProtocolConfig$SelectorFailureBehavior/NO_ADVERTISE
          ApplicationProtocolConfig$SelectedListenerFailureBehavior/ACCEPT
          [ApplicationProtocolNames/HTTP_2 ApplicationProtocolNames/HTTP_1_1]))
        (build))))
