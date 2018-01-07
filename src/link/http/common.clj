(ns link.http.common
  (:require [clojure.string :refer [lower-case]]
            [clojure.java.io :refer [input-stream copy]])
  (:import [java.io File InputStream]
           [io.netty.buffer ByteBuf ByteBufOutputStream ByteBufAllocator]))

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

(defn content-from-ring-body [body ^ByteBufAllocator alloc]
  (cond
    (nil? body) nil

    (instance? String body)
    (let [bytes (.getBytes ^String body "UTF-8")
          buffer (.buffer alloc (alength bytes))]
      (.writeBytes ^ByteBuf buffer ^bytes bytes)
      buffer)

    (sequential? body)
    (let [buffer (.buffer alloc)
          line-bytes (map #(.getBytes ^String % "UTF-8") body)]
      (doseq [line line-bytes]
        (.writeBytes ^ByteBuf buffer ^bytes line))
      buffer)

    (instance? File body)
    (let [buffer (.buffer alloc)
          buffer-out (ByteBufOutputStream. buffer)
          file-in (input-stream body)]
      (copy file-in buffer-out)
      buffer)

    (instance? InputStream body)
    (let [buffer (.buffer alloc (.available ^InputStream body))
          buffer-out (ByteBufOutputStream. buffer)]
      (copy body buffer-out)
      buffer)))
