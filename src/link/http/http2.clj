(ns link.http.http2
  (:require [link.core :refer :all]
            [link.http.common :refer :all]
            [clojure.string :as string]
            [clojure.tools.logging :as logging])
  (:import [java.net InetSocketAddress]
           [io.netty.buffer ByteBufInputStream]
           [io.netty.channel ChannelFuture SimpleChannelInboundHandler]
           [io.netty.handler.codec.http HttpResponseStatus
            HttpServerUpgradeHandler$UpgradeCodecFactory]
           [io.netty.handler.codec.http2 Http2FrameCodecBuilder
            Http2HeadersFrame Http2DataFrame Http2Headers Http2Headers$PseudoHeaderName
            DefaultHttp2Headers DefaultHttp2HeadersFrame DefaultHttp2DataFrame
            Http2CodecUtil Http2ServerUpgradeCodec]
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
    {:scheme (keyword (str (.scheme http2headers)))
     :protocol "h2c"
     :request-method (-> (.method http2headers) (string/lower-case) (keyword))
     :uri (find-request-uri uri)
     :query-string (find-query-string uri)
     :headers header-map
     :server-name (.getHostString ^InetSocketAddress server-addr)
     :server-port (.getPort ^InetSocketAddress server-addr)
     :remote-addr (.getHostString ^InetSocketAddress (remote-addr ch))}))

(defn ring-response-to-http2 [resp alloc]
  (let [resp (if (map? resp) resp {:body resp})
        {status :status headers :headers body :body} resp
        status (or status 200)
        content (content-from-ring-body body alloc)

        http2headers (doto (DefaultHttp2Headers.)
                       (.status (.codeAsText (HttpResponseStatus/valueOf status))))]
    ;; set headers
    (doseq [header (or headers {})]
      (.set ^DefaultHttp2Headers http2headers ^String (key header) ^Object (val header)))

    (if content
      [(DefaultHttp2HeadersFrame. http2headers)
       (DefaultHttp2DataFrame. content true)]
      [(DefaultHttp2HeadersFrame. http2headers true)])))

(defn http2-on-error [ch exc debug]
  (let [resp-frames (ring-response-to-http2 {:status 500
                                             :body (if debug
                                                     (str (.getStackTrace exc))
                                                     "Internal Error")}
                                            (.alloc ch))]
    ;; TODO: which stream?
    (doseq [f resp-frames]
      (send! ch f))))

(def ^:const http2-data-key "HTTP_DATA")

(defn- handle-full-request [ch msg ring-fn async? debug?]
  (when (.isEndStream msg)
    (let [ring-req (channel-attr-get ch http2-data-key)
          stream (.stream msg)]
      (if-not async?
        ;; sync
        (let [ring-resp (ring-fn ring-req)
              resp-frames (ring-response-to-http2 ring-resp (.alloc ch))]
          (doseq [f resp-frames]
            (send! ch (.stream f stream))))
        ;; async
        (let [send-fn (fn [resp]
                        (doseq [f (ring-response-to-http2 resp (.alloc ch))]
                          (send! ch (.stream f stream))))
              raise-fn (fn [error]
                         (http2-on-error ch error debug?))]
          (ring-fn ring-req send-fn raise-fn))))))

(defn http2-stream-handler [ring-fn async? debug?]
  (create-handler
   (on-message [ch msg]
               (println msg)
               (cond
                 (instance? Http2HeadersFrame msg)
                 (let [ring-data (ring-data-from-header ch msg)]
                   (channel-attr-set! ch http2-data-key ring-data)
                   (handle-full-request ch msg ring-fn async? debug?))

                 (instance? Http2DataFrame msg)
                 (let [body-in (ByteBufInputStream. (.content ^Http2DataFrame msg))
                       ring-data (channel-attr-get http2-data-key)]
                   (when (> (.available ^ByteBufInputStream body-in) 0)
                     (channel-attr-set! ch http2-data-key
                                        (assoc ring-data :body body-in)))
                   (handle-full-request ch msg ring-fn async? debug?)))
               )
   (on-error [ch exc]
             (logging/warn exc "Uncaught exception")
             (http2-on-error ch exc debug?))))

(defn http2-frame-handler []
  (.build (Http2FrameCodecBuilder/forServer)))

;; for h2c
(defn http2-upgrade-handler [ring-fn async? debug?]
  (reify HttpServerUpgradeHandler$UpgradeCodecFactory
    (newUpgradeCodec [this protocol]
      (when (= protocol Http2CodecUtil/HTTP_UPGRADE_PROTOCOL_NAME)
        (Http2ServerUpgradeCodec.
         (http2-frame-handler)
         (http2-stream-handler ring-fn async? debug?))))))
