(defproject link "0.6.4"
  :description "A clojure framework for nonblocking network programming"
  :url "http://github.com/sunng87/link"
  :license {:name "Eclipse Public License - v 1.0"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.5.1"]
                 [io.netty/netty-all "4.0.17.Final"]
                 [org.clojure/tools.logging "0.2.6"]]
  :profiles {:dev {:dependencies [[log4j/log4j "1.2.17"]]}}
  :scm {:name "git"
        :url "http://github.com/sunng87/link"}
  :global-vars {*warn-on-reflection* true})
