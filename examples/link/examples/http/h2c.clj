(ns link.examples.http.h2c
  (:require [link.http :as h]))

(defn ring-app [req]
  "<h1>It works</h1>")

(defn -main [& args]
  (println "Starting h2c server at 8080")
  (h/h2c-server 8080 ring-app))
