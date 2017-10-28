(ns link.http.http2
  (:import [io.netty.handler.ssl
            ApplicationProtocolNegotiationHandler
            ApplicationProtocolNames]
           [io.netty.handler.codec.http HttpServerCodec HttpObjectAggregator]
           [io.netty.handler.codec.http2 Http2MultiplexCodecBuilder]))

(defn http2-stream-handler [handler]
  )

(defn http2-alpn-handler [handler max-request-body]
  (proxy [ApplicationProtocolNegotiationHandler] [ApplicationProtocolNames/HTTP_1_1]
    (configurePipeline [ctx protocol]
      (cond
        (= protocol ApplicationProtocolNames/HTTP_2)
        [(.build (Http2MultiplexCodecBuilder/forServer (http2-stream-handler handler)))]

        (= protocol ApplicationProtocolNames/HTTP_1_1)
        [(HttpServerCodec.)
         (HttpObjectAggregator. max-request-body)
         handler]

        :else (IllegalStateException. "Unsupported ALPN Protocol")))))
