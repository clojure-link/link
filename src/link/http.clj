(ns link.http
  (:use [link core tcp])
  (:use [clojure.string :only [lower-case]])
  (:use [clojure.java.io :only [input-stream copy]])
  (:require [link.threads :as threads]
            [clojure.tools.logging :as logging])
  (:import [java.io File InputStream PrintStream])
  (:import [java.net InetSocketAddress])
  (:import [io.netty.buffer
            ByteBuf
            Unpooled
            ByteBufInputStream
            ByteBufOutputStream])
  (:import [io.netty.handler.codec.http
            HttpVersion
            FullHttpRequest
            FullHttpResponse
            HttpHeaders
            HttpHeaders$Names
            HttpHeaders$Values
            HttpRequestDecoder
            HttpObjectAggregator
            HttpResponseEncoder
            HttpResponseStatus
            DefaultFullHttpResponse])
  (:import [clojure.lang APersistentMap]))


(defn- as-header-map [headers]
  (apply hash-map
         (flatten (map #(vector (lower-case (key %))
                                (val %)) headers))))

(defn- find-query-string [^String uri]
  (if (< 0 (.indexOf uri "?"))
    (subs uri (+ 1 (.indexOf uri "?")))))

(defn- find-request-uri [^String uri]
  (if (< 0 (.indexOf uri "?"))
    (subs uri 0 (.indexOf uri "?"))
    uri))

(defn ring-request [ch req]
  (let [server-addr (channel-addr ch)
        uri (.getUri ^FullHttpRequest req)]
    {:server-addr (.getHostString ^InetSocketAddress server-addr)
     :server-port (.getPort ^InetSocketAddress server-addr)
     :remote-addr (.getHostString ^InetSocketAddress (remote-addr ch))
     :uri (find-request-uri uri)
     :query-string (find-query-string uri)
     :scheme :http
     :request-method (keyword (lower-case
                               (.. ^FullHttpRequest req getMethod name)))
     :content-type (HttpHeaders/getHeader
                    ^FullHttpRequest req HttpHeaders$Names/CONTENT_TYPE)
     :content-length (HttpHeaders/getContentLength req)
     :character-encoding (HttpHeaders/getHeader
                          req HttpHeaders$Names/CONTENT_ENCODING)
     :headers (as-header-map (.headers ^FullHttpRequest req))
     :body (let [cbis (ByteBufInputStream.
                       (.content ^FullHttpRequest req))]
             (if (> (.available ^ByteBufInputStream cbis) 0)
               cbis))}))

(defn ring-response [resp]
  (let [{status :status headers :headers body :body} resp
        status (or status 200)
        content (cond
                 (nil? body) (Unpooled/buffer 0)

                 (instance? String body)
                 (let [buffer (Unpooled/buffer)
                       bytes (.getBytes ^String body "UTF-8")]
                   (.writeBytes ^ByteBuf buffer ^bytes bytes)
                   buffer)

                 (sequential? body)
                 (let [buffer (Unpooled/buffer)
                       line-bytes (map #(.getBytes ^String % "UTF-8") body)]
                   (doseq [line line-bytes]
                     (.writeBytes ^ByteBuf buffer ^bytes line))
                   buffer)

                 (instance? File body)
                 (let [buffer (Unpooled/buffer)
                       buffer-out (ByteBufOutputStream. buffer)
                       file-in (input-stream body)]
                   (copy file-in buffer-out)
                   buffer)

                 (instance? InputStream body)
                 (let [buffer (Unpooled/buffer)
                       buffer-out (ByteBufOutputStream. buffer)]
                   (copy body buffer-out)
                   buffer))

        netty-response (DefaultFullHttpResponse.
                         HttpVersion/HTTP_1_1
                         (HttpResponseStatus/valueOf status)
                         content)

        netty-headers (.headers netty-response)]

    ;; write headers
    (doseq [header (or headers {})]
      (.set ^HttpHeaders netty-headers ^String (key header) ^Object (val header)))

    (.set ^HttpHeaders netty-headers
          ^String HttpHeaders$Names/CONTENT_LENGTH
          ^Object (.readableBytes content))

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

(defprotocol ResponseHandle
  (http-handle [resp ch req]))

(extend-protocol ResponseHandle
  APersistentMap
  (http-handle [resp ch _]
    (send! ch (ring-response resp))))

(defn create-http-handler-from-ring [ring-fn debug?]
  (create-handler
   (on-message [ch msg]
               (when (valid? ch)
                 (let [req  (ring-request ch msg)
                       resp (or (ring-fn req) {})]
                   (http-handle resp ch req))))

   (on-error [ch exc]
             (logging/warn exc "Uncaught exception")
             (http-on-error ch exc debug?))))

(defn create-http-handler-from-async-ring [ring-fn debug?]
  (create-handler
   (on-message [ch msg]
               (let [req (ring-request ch msg)
                     resp-fn (fn [resp]
                               (http-handle resp ch req))
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
