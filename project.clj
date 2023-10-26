(defproject com.github.enragedginger/clj-pool-party "0.1.0"
  :description "Minimalistic Clojure object pooling library"
  :url "https://github.com/enragedginger/clj-pool-party"
  :license {:name "EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}
  :dependencies [[org.clojure/clojure "1.11.1"]]
  :main ^:skip-aot com.github.enragedginger.clj-pool-party.core
  :target-path "target/%s"
  :profiles {:dev {:dependencies [[org.clojure/test.check "1.1.1"]
                                  [criterium "0.4.6"]]}})
