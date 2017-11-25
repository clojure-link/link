(ns link.examples.http.h2c
  (:require [link.http :as h]))

(defn ring-app [req]
  {:status 200
   :body (str "<h1>It works</h1>" req)})

(defn -main [& args]
  (println "Starting h2c server at 8080")
  (h/h2c-server 8080 ring-app :threads 8))
