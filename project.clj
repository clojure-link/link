(defproject link "0.5.0-SNAPSHOT"
  :description "A straightforward (not-so-clojure) clojure wrapper for java nio framework"
  :url "http://github.com/sunng87/link"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.5.1"]
                 [io.netty/netty-all "4.0.15.Final"]]
  :global-vars {*warn-on-reflection* true})
