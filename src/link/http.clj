(ns link.http
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
            MessageEvent])
  (:import [org.jboss.netty.buffer
            ChannelBuffers
            ChannelBufferInputStream
            ChannelBufferOutputStream])
  (:import [org.jboss.netty.bootstrap ServerBootstrap])
  (:import [org.jboss.netty.channel.socket.nio
            NioServerSocketChannelFactory])
  (:import [org.jboss.netty.handler.codec.http
            HttpVersion
            HttpRequest
            HttpHeaders
            HttpHeaders$Names
            HttpServerCodec
            HttpResponseStatus
            DefaultHttpResponse]))

(defn create-http-pipeline [handler]
  (reify ChannelPipelineFactory
    (getPipeline [this]
      (let [pipeline (Channels/pipeline)]
        (.addLast pipeline "codec" (HttpServerCodec.))
        (.addLast pipeline "handler" handler)
        pipeline))))

(defn- as-map [headers]
  (apply hash-map
         (flatten (map #(vector (key %) (val %)) headers))))

(defn- find-query-string [^String uri]
  (if (< 0 (.indexOf uri "?"))
    (subs uri (+ 1 (.indexOf uri) "?"))))

(defn ring-request [^Channel c ^MessageEvent e]
  (let [server-addr (.getLocalAddress c)
        addr (.getRemoteAddress e)
        req (.getMessage e)]
    {:server-addr (.getHostName server-addr)
     :server-port (.getPort server-addr)
     :remote-addr (.getHostName addr)
     :uri (.getUri req)
     :query-string (find-query-string (.getUri req))
     :scheme :http
     :request-method (keyword (lower-case (.. req getMethod getName)))
     :content-type (HttpHeaders/getHeader req HttpHeaders$Names/CONTENT_TYPE)
     :content-length (HttpHeaders/getContentLength req)
     :character-encoding (HttpHeaders/getHeader req HttpHeaders$Names/CONTENT_ENCODING)
     :headers (as-map (.getHeaders req))
     :body (ChannelBufferInputStream. (.getContent req))}))

(defn- set-content-length [resp length]
  (.setHeader resp HttpHeaders$Names/CONTENT_LENGTH length))

(defn ring-response [resp]
  (let [{status :status headers :headers body :body} resp
        netty-response (DefaultHttpResponse.
                         HttpVersion/HTTP_1_1
                         (HttpResponseStatus/valueOf status))]
    ;; write headers
    (doseq [header (or headers {})]
      (.setHeader netty-response (key header) (val header)))

    ;; write body
    (cond
     (instance? String body)
     (let [bytes (.getBytes body "UTF-8")]
       (set-content-length netty-response (alength bytes))
       (.setContent netty-response (ChannelBuffers/wrappedBuffer bytes)))
     (sequential? body)
     (let [buffer (ChannelBuffers/dynamicBuffer)
           line-bytes (map (memfn getBytes "UTF-8") body)
           content-length (reduce + line-bytes)]
       (doseq [line line-bytes]
         (.writeBytes buffer line))
       (set-content-length netty-response content-length)
       (.setContent netty-response buffer))
     (instance? File body)
     (let [buffer (ChannelBuffers/dynamicBuffer)
           buffer-out (ChannelBufferOutputStream. buffer)
           file-size (.length body)
           file-in (input-stream body)]
       (copy file-in buffer-out)
       (set-content-length netty-response file-size)
       (.setContent netty-response buffer))
     (instance? InputStream body)
     (let [buffer (ChannelBuffers/dynamicBuffer)
           buffer-out (ChannelBufferOutputStream. buffer)
           clength (.available body)]
       (copy body buffer-out)
       (set-content-length netty-response clength)
       (.setContent netty-response buffer)))
    
    netty-response))

(defn create-http-handler-from-ring [ring-fn]
  (create-handler
   (on-message [ctx e]
               (let [channel (.getChannel ctx)
                     req (ring-request channel e)
                     resp (ring-fn req)]
                  (.write channel (ring-response resp))))
   (on-error [ctx e]
             (let [resp (DefaultHttpResponse.
                          HttpVersion/HTTP_1_1
                          HttpResponseStatus/INTERNAL_SERVER_ERROR)
                   resp-buf (ChannelBuffers/dynamicBuffer)
                   resp-out (ChannelBufferOutputStream. resp-buf)]
               (-> (.getCause e)
                   (.printStackTrace (PrintStream. resp-out)))
               (.setContent resp resp-buf)
               (.write (.getChannel ctx) resp)))))

(defn http-server [port ring-fn
                   & {:keys [boss-pool worker-pool]
                      :or {boss-pool (Executors/newCachedThreadPool)
                           worker-pool (Executors/newCachedThreadPool)}}]
  (let [factory (NioServerSocketChannelFactory. boss-pool worker-pool)
        bootstrap (ServerBootstrap. factory)
        pipeline (create-http-pipeline
                  (create-http-handler-from-ring ring-fn))]
    (.setPipelineFactory bootstrap pipeline)
    (.bind bootstrap (InetSocketAddress. port))))

