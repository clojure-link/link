(ns link.http
  (:use [link.core])
  (:use [clojure.string :only [lower-case]])
  (:use [clojure.java.io :only [input-stream copy]])
  (:import [java.io File InputStream])
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
            DefaultHttpResponse
            HttpRequestDecoder
            HttpResponseEncoder]))

(defn create-http-pipeline [handler]
  (reify ChannelPipelineFactory
    (getPipeline [this]
      (let [pipeline (Channels/pipeline)]
        (.addLast pipeline "decoder" (HttpRequestDecoder.))
        (.addLast pipeline "encoder" (HttpResponseEncoder.))
        (.addLast pipeline "handler" handler)))))

(defn- as-map [headers]
  ;;TODO
  )

(defn ring-request [^Channel c ^MessageEvent e]
  (let [server-addr (.getLocalAddress c)
        addr (.getRemoteAddress e)
        req (.getMessage e)]
    {:server-addr (.getHostName server-addr)
     :server-port (.getPort server-addr)
     :remote-addr (.getHostName addr)
     :uri (.getUri req)
     :query-string (subs (.getUri req) (+ 1 (.indexOf (.getUri req) \?)))
     :scheme :http
     :request-method (keyword (lower-case (.. req getMethod getName)))
     :content-type (HttpHeaders/getHeader req HttpHeaders$Names/CONTENT_TYPE)
     :content-length (HttpHeaders/getContentLength req)
     :character-encoding (HttpHeaders/getHeader req HttpHeaders$Names/CONTENT_ENCODING)
     :headers (as-map (.getHeaders req))
     :body (ChannelBufferInputStream. (.getContent req))}))

(defn ring-response [resp]
  (let [{status :status headers :headers body :body} resp
        netty-response (DefaultHttpResponse. HttpVersion/HTTP_1_1 status)]
    
    ;; write headers
    (doseq [header headers]
      (.setHeader netty-response (key header) (val header)))

    ;; write body
    (cond
     (instance? String body)
     (.setContent netty-response (ChannelBuffers/wrappedBuffer (.getBytes body "UTF-8")))
     (sequential? body)
     (let [buffer (ChannelBuffers/dynamicBuffer)]
       (doseq [line body]
         (.writeBytes buffer (.getBytes line "UTF-8")))
       (.setContent netty-response buffer))
     (instance? File body)
     (let [buffer (ChannelBuffers/dynamicBuffer)
           buffer-out (ChannelBufferOutputStream. buffer)
           file-in (input-stream body)]
       (copy file-in buffer-out)
       (.setContent netty-response buffer))
     (instance? InputStream body)
     (let [buffer (ChannelBuffers/dynamicBuffer)
           buffer-out (ChannelBufferOutputStream. buffer)]
       (copy body buffer-out)
       (.setContent netty-response buffer)))
    
    netty-response))

(defn create-http-handler-from-ring [ring-fn]
  (create-handler
   (on-message [ctx e]
               ;;TODO
               )))

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

