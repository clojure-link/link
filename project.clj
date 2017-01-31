(def netty-version "4.1.6.Final")
(defproject link "0.10.0-SNAPSHOT"
  :description "A clojure framework for nonblocking network programming"
  :url "http://github.com/sunng87/link"
  :license {:name "Eclipse Public License - v 1.0"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [io.netty/netty-buffer ~netty-version]
                 [io.netty/netty-codec-http ~netty-version]
                 [io.netty/netty-codec ~netty-version]
                 [io.netty/netty-common ~netty-version]
                 [io.netty/netty-handler ~netty-version]
                 [io.netty/netty-transport ~netty-version]
                 [org.clojure/tools.logging "0.3.1"]]
  :profiles {:dev {:dependencies [[log4j/log4j "1.2.17"]]}}
  :scm {:name "git"
        :url "http://github.com/sunng87/link"}
  :global-vars {*warn-on-reflection* true}
  :deploy-repositories {"releases" :clojars})
