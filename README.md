# link

link is the event-driven network library used by
[slacker](https://github.com/sunng87/slacker). It's a thin wrapper of Netty.

## Usage

### Leiningen

![https://clojars.org/link](https://clojars.org/link/latest-version.svg)

Currently, link only works on the JVM implementation of Clojure. We
might support nodejs in future.

### API

#### Codec

In most cases, we use a declarative DSL to define a custom tcp
protocol codec: `link.codec`.

With the codec, you can read/write Clojure data structure in your
handler and don't have to read the message byte by byte, and  worry
about TCP framing.

```clojure
user> (use 'link.codec)

;; create a custom codec: [version target-id string-message]
user> (def custom-codec
  (frame
    (byte)
    (int32)
    (string :encoding :utf8 :prefix (uint16))))

;; encode. note that you don't have to call `encode*` and `decode*` by
;; youself, link does it for you.
user> (encode* custom-codec [1 348 "hello world"])
#<UnpooledHeapByteBuf UnpooledHeapByteBuf(ridx: 0, widx: 18, cap: 256)>

user> (decode* custom-codec *1)
[1 348 "hello world"]
```

For a more complex codec, check <a
href="https://github.com/sunng87/slacker/blob/master/src/slacker/protocol.clj">slacker's
codec definition</a>.

#### handler

You need to create a custom handler to process you network
message. Link has provided you a dsl that is easier to understand. And
also hide complexity of Netty's default handler API.

```clojure
(use 'link.core)

(def echo-handler
  (create-handler
    (on-message [ch msg]
      (send ch msg))))
```

In link 0.5, there are four events you can process in handler

* `(on-active [ch])` when channel is open, bound or connected
* `(on-inacitve [ch])` when channel is no longer open, bound or connected
* `(on-message [ch msg])` when a packet is read in
* `(on-error [ch e])` when exception occurs in I/O thread

And for the channel `ch`, you can call following functions as defined
by `LinkMessageChannel` protocol.

* `(send [ch msg])` write a msg into channel
* `(channel-addr [ch])` get the local socket address of the channel
* `(remote-addr [ch])` get the remote socket address of the channel
* `(close [ch])` request to close the channel
* `(valid? [ch])` test if channel is still open and active

#### the TCP server

link only supports non-blocking server and client.

To start a server, you can provide a few argument to customize it:

```clojure
(use '[link threads tcp])

;; I just demo the usage here, there is no need to run a echo-handler
;; in a thread pool.
(def handler-spec {:handler echo-handler :executor (new-executor 10)})

;; you can also provide a few handlers by passing a vector of them
(tcp-server 8081 handler-spec
            :codec custom-codec ;; your codec defined with link.codec

            :options {:so-reuseaddr true} ;; netty, ip, tcp and socket options
            :ssl-content ... ;; a SSLContext object for ssl server
            :host ;; if to bind, default "0.0.0.0"

```

See a full list of options in `link.core/channel-option`. You need to
prefixing a `clild-` to specify option for child channels:
`:child-tcp-nodelay`

In link 0.5, you can stop a server by
``` clojure
;; calling stop-server with the value returned by tcp-server
(stop-server *1)
```
#### the TCP client

To create a TCP client, you need to create a connection factory for
it. Note that, clients created from the same factory will share the
same selector and event loop. Managing it carefully if you have a
large number of connections.

```clojure
(def client-factory
  (tcp-client-factory handlers
                      :codec custom-codec
                      :options ...
                      :ssl-context))
```

Create a client

```clojure
(tcp-client client-factory "localhost" 8081)
```

The value returned by `tcp-client` is a `LinkMessageChannel` object so
you can call any functions of the protocol on it.

To close a client, call `close` on the channel. To close a client
factory, call `stop-clients` would work.


#### HTTP Server

link also comes with an HTTP server. Since link is a clojure library,
it accepts a ring function, so you can use any HTTP framework on link
http server, without pain.

```clojure
(use 'link.http)

(http-server 8080 ring-app-fn
             :executor ... ;; the thread pool to run ring functions on)
```

#### Websocket

New in link 0.5. You can start a websocket server with link.

Create a websocket handler:

```clojure
(use 'link.websocket)

(def ws-echo-handler
  (on-open [ch])
  (on-close [ch])
  (on-text [ch string]
    ;; you can use (text), (binary), (ping), (pong) to generate
    ;; different types of response
    (send ch (text string)))
  (on-binary [ch ^ByteBuf bytes])
  (on-ping [ch ^ByteBuf bytes])
  (on-pong [ch ^ByteBuf bytes]))

(tcp-server 8082 (conj (websocket-codecs "/chat") ws-handler))

```

## License

Copyright (C) 2012-2014 Sun Ning <sunng@about.me>

Distributed under the Eclipse Public License, the same as Clojure.
