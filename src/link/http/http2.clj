(ns link.http.http2
  (:require [link.core :refer :all]
            [clojure.string :as string])
  (:import [io.netty.handler.ssl
            ApplicationProtocolNegotiationHandler
            ApplicationProtocolNames]
           [io.netty.handler.codec.http HttpServerCodec HttpObjectAggregator]
           [io.netty.handler.codec.http2 Http2MultiplexCodecBuilder
            Http2HeadersFrame Http2DataFrame Http2Headers Http2Headers$PseudoHeaderName]))

(defn from-header-iterator
  "extract ring headers from http2headers"
  [http2headers-iterator]
  (->> (seq http2headers-iterator)
       (filter #(not (Http2Headers$PseudoHeaderName/isPseudoHeader (.getKey %))))
       (map #(vector (string/lower-case (.getKey %))
                     (.getValue %)))
       (flatten)
       (apply hash-map)))


(defn headers-from-http2-header-frame [^Http2HeadersFrame frame]
  (let [http2headers (.headers frame)]
    {:scheme (.scheme http2headers)
     :request-method (.method http2headers)
     :uri (.path http2headers)
     :headers (from-header-iterator (.iterator http2headers))}))

(defn http2-stream-handler [handler]
  (let [headers (atom {})]
    (create-handler
     (on-message [ch msg]
                 (cond
                   (instance? Http2HeadersFrame msg)
                   (let [header (headers-from-http2-header-frame msg)]
                     (reset! headers header))

                   (instance? Http2DataFrame msg)
                   (do)

                   )))))

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
