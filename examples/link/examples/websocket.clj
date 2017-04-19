(ns link.examples.websocket
  (:require [link.websocket :refer :all]
            [link.tcp :refer :all]
            [link.core :refer [send!]]))

(def handler
  (create-websocket-handler
   (on-text [ch msg]
            (send! ch msg))
   (on-error [ch e]
             (.printStackTrace e))))

(defn -main [& args]
  (println ";; Starting websocket server")
  (tcp-server 8080
              (conj (websocket-codecs "/" :compression? true)
                    websocket-auto-frame-encoder
                    handler))
  (println ";; Websocket server started on 8080"))
