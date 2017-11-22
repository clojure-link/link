(ns link.examples.http.h2
  (:require [link.http :as h]
            [link.ssl :as ssl])
  (:import [io.netty.handler.ssl SslContextBuilder SslProvider]
           [io.netty.handler.ssl.util SelfSignedCertificate]))

(def ssl-context
  (let [ssc (SelfSignedCertificate.)]
    (.. (SslContextBuilder/forServer (.certificate ssc) (.privateKey ssc))
      (sslProvider SslProvider/JDK)
      (build))))

(defn ring-app [req]
  {:status 200
   :body (str "<h1>It works</h1>" req)})

(defn -main [& args]
  (println "Starting h2 server at 8443")
  (let [ssc (SelfSignedCertificate.)
        ssl-context (ssl/ssl-context-for-http2 (.certificate ssc) (.privateKey ssc)
                                               :jdk)]
    (h/h2-server 8443 ring-app ssl-context :threads 8)))
