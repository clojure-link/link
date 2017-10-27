(ns link.examples.echo
  (:require [link.core :refer :all]
            [link.tcp :as tcp]
            [link.codec :as codec]
            [clojure.tools.logging :as logging]))

(def echo-codec
  (codec/netty-codec
   (codec/frame
    (codec/string :delimiter "\r\n" :encoding "utf8"))))

(def echo-server-handler
  (create-handler
   (on-message [ch msg]
               (logging/infof "TCP:Server: Received %s from %s" msg ch)
               (send! ch msg))))

(def echo-client-handler
  (create-handler
   (on-message [ch msg]
               (logging/infof "TCP:Client: Received %s from %s" msg ch))))

(defn -main [& args]
  (let [port 8715]
    (tcp/tcp-server port [echo-codec echo-server-handler])
    (logging/infof "TCP Server started on port %s" port)
    (let [client-factory (tcp/tcp-client-factory [echo-codec echo-client-handler])
          client (tcp/tcp-client client-factory "127.0.0.1" port)]
      (logging/infof "TCP Client connected on port %s" port)
      (send! client ["Link echo example!\r\n"]))))
