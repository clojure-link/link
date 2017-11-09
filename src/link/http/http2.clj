(ns link.http.http2
  (:require [link.core :refer :all]
            [link.http.common :refer :all]
            [clojure.string :as string])
  (:import [java.net InetSocketAddress]
           [io.netty.handler.ssl
            ApplicationProtocolNegotiationHandler
            ApplicationProtocolNames]
           [io.netty.buffer ByteBufInputStream]
           [io.netty.channel ChannelFuture]
           [io.netty.handler.codec.http HttpServerCodec HttpObjectAggregator
            HttpResponseStatus HttpServerUpgradeHandler$UpgradeCodecFactory]
           [io.netty.handler.codec.http2 Http2MultiplexCodecBuilder
            Http2HeadersFrame Http2DataFrame Http2Headers Http2Headers$PseudoHeaderName
            DefaultHttp2Headers DefaultHttp2HeadersFrame DefaultHttp2DataFrame
            Http2CodecUtil Http2ServerUpgradeCodec
            Http2MultiplexCodec$DefaultHttp2StreamChannel]
           [io.netty.util.concurrent GenericFutureListener]))

(defn from-header-iterator
  "extract ring headers from http2headers"
  [http2headers-iterator]
  (as-header-map (iterator-seq http2headers-iterator)))

(defn ring-data-from-header [ch ^Http2HeadersFrame frame]
  (let [server-addr (channel-addr ch)
        http2headers (.headers frame)
        uri (str (.path http2headers))
        header-map (from-header-iterator (.iterator http2headers))]
    {:scheme (.scheme http2headers)
     :request-method (-> (.method http2headers) (string/lower-case) (keyword))
     :uri (find-request-uri uri)
     :query-string (find-request-uri uri)
     :headers header-map
     :server-addr (.getHostString ^InetSocketAddress server-addr)
     :server-port (.getPort ^InetSocketAddress server-addr)
     :remote-addr (.getHostString ^InetSocketAddress (remote-addr ch))}))

(defn ring-response-to-http2 [resp]
  (let [resp (if (map? resp) resp {:body resp})
        {status :status headers :headers body :body} resp
        status (or status 200)
        content (content-from-ring-body body)

        http2headers (doto (DefaultHttp2Headers.)
                       (.status (.codeAsText (HttpResponseStatus/valueOf status))))]
    ;; set headers
    (doseq [header (or headers {})]
      (.set ^DefaultHttp2Headers http2headers ^String (key header) ^Object (val header)))

    (if content
      [(DefaultHttp2HeadersFrame. http2headers)
       (DefaultHttp2DataFrame. content true)]
      [(DefaultHttp2HeadersFrame. http2headers true)])))

(extend-protocol LinkMessageChannel
  Http2MultiplexCodec$DefaultHttp2StreamChannel
  (id [this]
    (channel-id this))
  (send! [this msg]
    (send!* this msg nil))
  (send!* [this msg cb]
    (let [cf (.writeAndFlush this msg)]
      (when cb
        (.addListener ^ChannelFuture cf (reify GenericFutureListener
                                          (operationComplete [this f] (cb f)))))))
  (channel-addr [this]
    (.localAddress this))
  (remote-addr [this]
    (.remoteAddress this))
  (close! [this]
    (.close this))
  (valid? [this]
    (.isActive this)))

(defn http2-stream-handler [ring-fn]
  (let [request (atom {})]
    (create-handler
     (on-message [ch msg]
                 (cond
                   (instance? Http2HeadersFrame msg)
                   (let [ring-data (ring-data-from-header ch msg)]
                     (swap! request merge ring-data))

                   (instance? Http2DataFrame msg)
                   (let [body-in (ByteBufInputStream. (.content ^Http2DataFrame msg))]
                     (when (> (.available ^ByteBufInputStream body-in) 0)
                       (swap! request assoc :body body-in))))
                 (when (.isEndStream msg)
                   (let [ring-resp (ring-fn @request)
                         resp-frames (ring-response-to-http2 ring-resp)]
                     (doseq [f resp-frames]
                       (send! ch f))))))))

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

;; for h2c
(defn http2-upgrade-handler [ring-fn]
  (reify HttpServerUpgradeHandler$UpgradeCodecFactory
    (newUpgradeCodec [this protocol]
      (when (= protocol Http2CodecUtil/HTTP_UPGRADE_PROTOCOL_NAME)
        (Http2ServerUpgradeCodec.
         (.build (Http2MultiplexCodecBuilder/forServer
                  (http2-stream-handler ring-fn))))))))
