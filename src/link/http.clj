(ns link.http
  (:use [link core tcp])
  (:use [clojure.string :only [lower-case]])
  (:use [clojure.java.io :only [input-stream copy]])
  (:require [link.threads :as threads]
            [link.http.common :refer :all]
            [link.http.http2 :as h2]
            [link.ssl :as ssl]
            [clojure.tools.logging :as logging])
  (:import [java.io File InputStream PrintStream])
  (:import [java.net InetSocketAddress])
  (:import [io.netty.buffer
            ByteBuf
            Unpooled
            ByteBufInputStream
            ByteBufOutputStream]
           [io.netty.channel ChannelHandler SimpleChannelInboundHandler]
           [io.netty.handler.codec.http
            HttpVersion
            FullHttpRequest
            FullHttpResponse
            HttpHeaders
            HttpHeaders$Names
            HttpHeaders$Values
            HttpServerCodec
            HttpServerUpgradeHandler
            HttpRequestDecoder
            HttpObjectAggregator
            HttpResponseEncoder
            HttpResponseStatus
            DefaultFullHttpResponse]
           [io.netty.handler.ssl
            SslContext
            ApplicationProtocolNegotiationHandler
            ApplicationProtocolNames]
           [io.netty.util ReferenceCountUtil])
  (:import [clojure.lang APersistentMap]))


(defn ring-request [ch req]
  (let [server-addr (channel-addr ch)
        uri (.getUri ^FullHttpRequest req)]
    {:server-name (.getHostString ^InetSocketAddress server-addr)
     :server-port (.getPort ^InetSocketAddress server-addr)
     :remote-addr (.getHostString ^InetSocketAddress (remote-addr ch))
     :uri (find-request-uri uri)
     :query-string (find-query-string uri)
     :scheme :http
     :protocol "HTTP/1.1"
     :request-method (keyword (lower-case
                               (.. ^FullHttpRequest req getMethod name)))
     :headers (as-header-map (.headers ^FullHttpRequest req))
     :body (let [cbis (ByteBufInputStream.
                       (.content ^FullHttpRequest req))]
             (when (> (.available ^ByteBufInputStream cbis) 0)
               cbis))}))

(defn ring-response [resp alloc]
  (let [resp (if (map? resp) resp {:body resp :headers {"Content-Type" "text/html"}})
        {status :status headers :headers body :body} resp
        status (or status 200)
        content (content-from-ring-body body alloc)

        netty-response (DefaultFullHttpResponse.
                         HttpVersion/HTTP_1_1
                         (HttpResponseStatus/valueOf status)
                         (or content (Unpooled/buffer 0)))

        netty-headers (.headers netty-response)]

    ;; write headers
    (doseq [header (or headers {})]
      (.set ^HttpHeaders netty-headers ^String (key header) ^Object (val header)))

    (.set ^HttpHeaders netty-headers
          ^String HttpHeaders$Names/CONTENT_LENGTH
          ^Object (if content (.readableBytes content) 0))

    (.set ^HttpHeaders netty-headers
          ^String HttpHeaders$Names/CONNECTION
          ^Object HttpHeaders$Values/KEEP_ALIVE)

    netty-response))

(defn http-on-error [ch exc debug]
  (let [resp-buf (Unpooled/buffer)
        resp-out (ByteBufOutputStream. resp-buf)
        resp (DefaultFullHttpResponse.
               HttpVersion/HTTP_1_1
               HttpResponseStatus/INTERNAL_SERVER_ERROR
               resp-buf)]
    (if debug
      (.printStackTrace exc (PrintStream. resp-out))
      (.writeBytes resp-buf (.getBytes "Internal Error" "UTF-8")))
    (send! ch resp)
    (close! ch)))

(defn create-http-handler-from-ring [ring-fn debug?]
  (create-handler
   (on-message [ch msg]
               (when (valid? ch)
                 (let [req  (ring-request ch msg)
                       resp (or (ring-fn req) {})]
                   (send! ch (ring-response resp (.alloc ch))))))

   (on-error [ch exc]
             (logging/warn exc "Uncaught exception")
             (http-on-error ch exc debug?))))

(defn create-http-handler-from-async-ring [ring-fn debug?]
  (create-handler
   (on-message [ch msg]
               (let [req (ring-request ch msg)
                     resp-fn (fn [resp]
                               (send! ch (ring-response resp (.alloc ch))))
                     raise-fn (fn [error]
                                (http-on-error ch error debug?))]
                 (ring-fn req resp-fn raise-fn)))

   (on-error [ch exc]
             (logging/warn exc "Uncaught exception")
             (http-on-error ch exc debug?))))

(defn http-server [port ring-fn
                   & {:keys [threads executor debug host
                             max-request-body async?
                             options]
                      :or {threads nil
                           executor nil
                           debug false
                           host "0.0.0.0"
                           max-request-body 1048576}}]
  (let [executor (if threads (threads/new-executor threads) executor)
        ring-handler (if async?
                       (create-http-handler-from-async-ring ring-fn debug)
                       (create-http-handler-from-ring ring-fn debug))
        handlers [(fn [_] (HttpRequestDecoder.))
                  (fn [_] (HttpObjectAggregator. max-request-body))
                  (fn [_] (HttpResponseEncoder.))
                  {:executor executor
                   :handler ring-handler}]]
    (tcp-server port handlers
                :host host
                :options options)))

(defprotocol Header
  (get-header [this key])
  (set-header [this key val]))

(extend HttpHeaders
  Header
  {:get-header #(.get %1 ^String %2)
   :set-header #(.set %1 ^String %2 %3)})

;; h2c handlers, fallback to http 1.1 if no upgrade
(defn h2c-handlers [ring-fn max-length executor debug? async?]
  (let [http-server-codec (HttpServerCodec.)
        upgrade-handler (HttpServerUpgradeHandler. http-server-codec
                                                   (h2/http2-upgrade-handler ring-fn async? debug?))]
    [http-server-codec
     upgrade-handler
     (proxy [SimpleChannelInboundHandler] []
       (channelRead0 [ctx msg]
         (let [ppl (.pipeline ctx)
               this-ctx (.context ppl this)]
           (.addAfter ppl executor (.name this-ctx) nil
                      (if async?
                        (create-http-handler-from-async-ring ring-fn debug?)
                        (create-http-handler-from-ring ring-fn debug?)))
           (.replace ppl this nil (HttpObjectAggregator. max-length)))
         (.fireChannelRead ctx (ReferenceCountUtil/retain msg))))]))

(defn h2c-server [port ring-fn
                  & {:keys [threads executor debug host
                            max-request-body async?
                            options]
                     :or {threads nil
                          executor nil
                          debug false
                          host "0.0.0.0"
                          max-request-body 1048576}}]
  (let [executor (if threads (threads/new-executor threads) executor)
        handler-spec (fn [_] (h2c-handlers ring-fn max-request-body executor debug async?))]
    (tcp-server port handler-spec :host host :options options)))

(defn http2-alpn-handler [ring-fn executor max-request-body async? debug?]
  (proxy [ApplicationProtocolNegotiationHandler] [ApplicationProtocolNames/HTTP_1_1]
    (configurePipeline [ctx protocol]
      (cond
        (= protocol ApplicationProtocolNames/HTTP_2)
        (.addLast (.pipeline ctx)
                  executor
                  (into-array ChannelHandler
                              [(h2/http2-frame-handler)
                               (h2/http2-stream-handler ring-fn async? debug?)]))

        (= protocol ApplicationProtocolNames/HTTP_1_1)
        (do
          (.addLast (.pipeline ctx)
                    (into-array ChannelHandler
                                [(HttpServerCodec.)
                                 (HttpObjectAggregator. max-request-body)]))
          (.addLast (.pipeline ctx)
                    executor
                    (into-array ChannelHandler
                                [(if async?
                                   (create-http-handler-from-async-ring ring-fn debug?)
                                   (create-http-handler-from-ring ring-fn debug?))])))

        :else (throw (IllegalStateException. "Unsupported ALPN Protocol"))))))

(defn h2-server
  "start http2 server"
  [port ring-fn ^SslContext ssl-context
   & {:keys [threads executor debug host
             max-request-body async? options]
      :or {threads nil
           executor nil
           debug false
           host "0.0.0.0"
           max-request-body 1048576}}]
  (let [executor (if threads (threads/new-executor threads) executor)
        handler-spec [(ssl/ssl-handler ssl-context)
                      (fn [_] (http2-alpn-handler ring-fn executor max-request-body
                                                async? debug))]]
    (tcp-server port handler-spec :host host :options options)))
