(defproject com.thheller/shadow-css "0.6.5"
  :description "CSS-in-CLJ(S)"
  :url "https://github.com/thheller/shadow-css"

  :license
  {:name "Eclipse Public License"
   :url "http://www.eclipse.org/legal/epl-v10.html"}

  :repositories
  {"clojars" {:url "https://clojars.org/repo"
              :sign-releases false}}

  :dependencies
  [[org.clojure/clojure "1.12.6" :scope "provided"]
   [org.clojure/clojurescript "1.11.132" :scope "provided"]
   [org.clojure/tools.reader "1.6.0"]]

  :source-paths
  ["src/main"])
