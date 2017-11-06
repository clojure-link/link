(ns link.examples.http.async
  (:require [link.http :as h]))

(defn ring-app [req resp-fn raise-fn]
  (resp-fn "<h1>It works</h1>"))

(defn -main [& args]
  (h/http-server 8080 ring-app :async? true))
