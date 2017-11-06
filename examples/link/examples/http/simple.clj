(ns link.examples.http.simple
  (:require [link.http :as h]))

(defn ring-app [req]
  "<h1>It works</h1>")

(defn -main [& args]
  (h/http-server 8080 ring-app))
