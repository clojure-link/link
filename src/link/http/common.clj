(ns link.http.common
  (:require [clojure.string :refer [lower-case]]))

(defn as-header-map [headers]
  (apply hash-map
         (flatten (map #(vector (lower-case (key %))
                                (val %)) headers))))

(defn find-query-string [^String uri]
  (if (< 0 (.indexOf uri "?"))
    (subs uri (+ 1 (.indexOf uri "?")))))

(defn find-request-uri [^String uri]
  (if (< 0 (.indexOf uri "?"))
    (subs uri 0 (.indexOf uri "?"))
    uri))
