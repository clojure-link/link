(defproject link "0.6.13"
  :description "A clojure framework for nonblocking network programming"
  :url "http://github.com/sunng87/link"
  :license {:name "Eclipse Public License - v 1.0"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.6.0"]
                 [io.netty/netty-buffer "4.0.21.Final"]
                 [io.netty/netty-codec-http "4.0.21.Final"]
                 [io.netty/netty-codec "4.0.21.Final"]
                 [io.netty/netty-common "4.0.21.Final"]
                 [io.netty/netty-handler "4.0.21.Final"]
                 [io.netty/netty-transport "4.0.21.Final"]
                 [org.clojure/tools.logging "0.2.6"]]
  :profiles {:dev {:dependencies [[log4j/log4j "1.2.17"]]}}
  :scm {:name "git"
        :url "http://github.com/sunng87/link"}
  :global-vars {*warn-on-reflection* true}
  :deploy-repositories {"releases" :clojars})
