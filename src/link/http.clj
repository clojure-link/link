(ns link.http
  (:refer-clojure :exclude [send])
  (:use [link.core])
  (:use [clojure.string :only [lower-case]])
  (:use [clojure.java.io :only [input-stream copy]])
  (:import [java.io File InputStream PrintStream])
  (:import [java.net InetSocketAddress])
  (:import [java.util.concurrent Executors])
  (:import [org.jboss.netty.channel
            Channel
            Channels
            ChannelPipelineFactory
            ChannelHandlerContext
            MessageEvent
            ExceptionEvent])
  (:import [org.jboss.netty.buffer
            ChannelBuffer
            ChannelBuffers
            ChannelBufferInputStream
            ChannelBufferOutputStream])
  (:import [org.jboss.netty.bootstrap ServerBootstrap])
  (:import [org.jboss.netty.channel.socket.nio
            NioServerSocketChannelFactory])
  (:import [org.jboss.netty.handler.codec.http
            HttpVersion
            HttpRequest
            HttpResponse
            HttpHeaders
            HttpHeaders$Names
            HttpServerCodec
            HttpResponseStatus
            DefaultHttpResponse]))

(defn create-http-pipeline [handler threaded?]
  (reify ChannelPipelineFactory
    (getPipeline [this]
      (let [pipeline (Channels/pipeline)]
        (.addLast pipeline "codec" (HttpServerCodec.))
        (if threaded?
          (.addLast pipeline "executor" (threaded-handler true)))
        (.addLast pipeline "handler" handler)
        pipeline))))

(defn- as-map [headers]
  (apply hash-map
         (flatten (map #(vector (key %) (val %)) headers))))

(defn- find-query-string [^String uri]
  (if (< 0 (.indexOf uri "?"))
    (subs uri (+ 1 (.indexOf uri "?")))))

(defn- find-request-uri [^String uri]
  (if (< 0 (.indexOf uri "?"))
    (subs uri 0 (.indexOf uri "?"))
    uri))

(defn ring-request [ch req addr]
  (let [server-addr (channel-addr ch)
        uri (.getUri ^HttpRequest req)]
    {:server-addr (.getHostName ^InetSocketAddress server-addr)
     :server-port (.getPort ^InetSocketAddress server-addr)
     :remote-addr (.getHostName ^InetSocketAddress addr)
     :uri (find-request-uri uri)
     :query-string (find-query-string uri)
     :scheme :http
     :request-method (keyword (lower-case
                               (.. ^HttpRequest req getMethod getName)))
     :content-type (HttpHeaders/getHeader
                    req HttpHeaders$Names/CONTENT_TYPE)
     :content-length (HttpHeaders/getContentLength req)
     :character-encoding (HttpHeaders/getHeader
                          req HttpHeaders$Names/CONTENT_ENCODING)
     :headers (as-map (.getHeaders ^HttpRequest req))
     :body (let [cbis (ChannelBufferInputStream.
                       (.getContent ^HttpRequest req))]
             (if (> (.available ^ChannelBufferInputStream cbis) 0)
               cbis))}))

(defn- write-content [resp buffer]
  (.setHeader ^HttpResponse resp
              ^String HttpHeaders$Names/CONTENT_LENGTH
              ^Integer (.readableBytes ^ChannelBuffer buffer))
  (.setContent ^HttpResponse resp buffer))

(defn ring-response [resp]
  (let [{status :status headers :headers body :body} resp
        netty-response (DefaultHttpResponse.
                         HttpVersion/HTTP_1_1
                         (HttpResponseStatus/valueOf status))]
    ;; write headers
    (doseq [header (or headers {})]
      (.setHeader ^HttpResponse  netty-response
                  ^String (key header)
                  ^Integer (val header)))

    ;; write body
    (cond
     (nil? body)
     (.setHeader ^HttpResponse resp
                 ^String HttpHeaders$Names/CONTENT_LENGTH
                 ^Integer (int 0))
     
     (instance? String body)
     (let [buffer (ChannelBuffers/dynamicBuffer)
           bytes (.getBytes ^String body "UTF-8")]
       (.writeBytes ^ChannelBuffer buffer ^bytes bytes)
       (write-content netty-response buffer))
     
     (sequential? body)
     (let [buffer (ChannelBuffers/dynamicBuffer)
           line-bytes (map #(.getBytes ^String % "UTF-8") body)]
       (doseq [line line-bytes]
         (.writeBytes ^ChannelBuffer buffer ^bytes line))
       (write-content netty-response buffer))
     
     (instance? File body)
     (let [buffer (ChannelBuffers/dynamicBuffer)
           buffer-out (ChannelBufferOutputStream. buffer)
           file-size (.length ^File body)
           file-in (input-stream body)]
       (copy file-in buffer-out)
       (write-content netty-response buffer))
     
     (instance? InputStream body)
     (let [buffer (ChannelBuffers/dynamicBuffer)
           buffer-out (ChannelBufferOutputStream. buffer)
           clength (.available ^InputStream body)]
       (copy body buffer-out)
       (write-content netty-response buffer)))
    
    netty-response))

(defn create-http-handler-from-ring [ring-fn debug]
  (create-handler
   (on-message [ch msg addr]
               (let [req (ring-request ch msg addr)
                     resp (ring-fn req)]
                  (send ch (ring-response resp))))
   (on-error [ch exc]
             (let [resp (DefaultHttpResponse.
                          HttpVersion/HTTP_1_1
                          HttpResponseStatus/INTERNAL_SERVER_ERROR)
                   resp-buf (ChannelBuffers/dynamicBuffer)
                   resp-out (ChannelBufferOutputStream. resp-buf)]
               
               (if debug
                 (.printStackTrace exc (PrintStream. resp-out))
                 (.writeBytes resp-buf (.getBytes "Internal Error" "UTF-8")))

               (write-content resp resp-buf)
               (send ch resp)))))

(defn http-server [port ring-fn
                   & {:keys [threaded? debug]
                      :or {threaded? false
                           debug false}}]
  (let [factory (NioServerSocketChannelFactory.
                 (Executors/newCachedThreadPool)
                 (Executors/newCachedThreadPool))
        bootstrap (ServerBootstrap. factory)
        pipeline (create-http-pipeline
                  (create-http-handler-from-ring ring-fn debug) threaded?)]
    (.setPipelineFactory bootstrap pipeline)
    (link.core.Server. bootstrap (.bind bootstrap (InetSocketAddress. port)))))

