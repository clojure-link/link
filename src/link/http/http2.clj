(ns link.http.http2
  (:require [link.core :refer :all]
            [link.http.common :refer :all]
            [clojure.string :as string])
  (:import [java.net InetSocketAddress]
           [io.netty.handler.ssl
            ApplicationProtocolNegotiationHandler
            ApplicationProtocolNames]
           [io.netty.buffer ByteBufInputStream]
           [io.netty.handler.codec.http HttpServerCodec HttpObjectAggregator]
           [io.netty.handler.codec.http2 Http2MultiplexCodecBuilder
            Http2HeadersFrame Http2DataFrame Http2Headers Http2Headers$PseudoHeaderName]))

(defn from-header-iterator
  "extract ring headers from http2headers"
  [http2headers-iterator]
  (as-header-map (seq http2headers-iterator)))

(defn ring-data-from-header [ch ^Http2HeadersFrame frame]
  (let [server-addr (channel-addr ch)
        http2headers (.headers frame)
        header-map (from-header-iterator (.iterator http2headers))]
    {:scheme (.scheme http2headers)
     :request-method (.method http2headers)
     :uri (find-request-uri (.path http2headers))
     :query-string (find-request-uri (.path http2headers))
     :headers header-map
     :content-type (get header-map "content-type")
     :content-length (get header-map "content-length")
     :character-encoding (get header-map "content-encoding")
     :server-addr (.getHostString ^InetSocketAddress server-addr)
     :server-port (.getPort ^InetSocketAddress server-addr)
     :remote-addr (.getHostString ^InetSocketAddress (remote-addr ch))}))

(defn ring-response-to-http2 [resp]
  )

(defn http2-stream-handler [handler]
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
                   (handler @request))))))

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
