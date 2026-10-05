(ns urpx.provenance-test
  "The build-provenance writer `clojure -T:build ci` embeds in the jar, run
   here into a temp dir: once on this checkout, once on a detached HEAD."
  (:require [babashka.fs :as fs]
            [build-provenance.write :as bp]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(def ^:private lib 'energy.grid-coordination/clj-urpx)

(defn- git [& args]
  (let [{:keys [exit out err]} (apply sh/sh "git" args)]
    (when-not (zero? exit)
      (throw (ex-info (str "git " (str/join " " args) " failed: " err) {:args args})))
    (str/trim out)))

(deftest test-alias-runs-the-builds-writer
  (let [aliases (:aliases (edn/read-string (slurp "deps.edn")))]
    (is (= (get-in aliases [:build :deps 'com.dcj/build-provenance])
           (get-in aliases [:test :extra-deps 'com.dcj/build-provenance])))))

(deftest provenance-names-this-checkout
  (let [dir (fs/create-temp-dir)]
    (try
      (let [{:keys [edn json]} (bp/write! {:lib lib :version "1.2.3" :class-dir (str dir)})
            pv (edn/read-string (slurp edn))]
        (testing "at the entry deploy's jar-provenance reads"
          (is (= (str (fs/path dir "META-INF/energy.grid-coordination/clj-urpx/build-provenance.edn"))
                 edn)))
        (is (.isFile (io/file json)))
        (is (= {:artifact           "energy.grid-coordination/clj-urpx"
                :version            "1.2.3"
                :commit             (git "rev-parse" "HEAD")
                :commit-abbreviated (git "rev-parse" "--short" "HEAD")
                :branch             (not-empty (git "branch" "--show-current"))}
               (select-keys pv [:artifact :version :commit :commit-abbreviated :branch])))
        (is (string? (:describe pv)))
        (is (boolean? (:dirty pv))))
      (finally (fs/delete-tree dir)))))

(deftest provenance-on-a-detached-head
  (let [dir (fs/create-temp-dir)]
    (try
      (sh/with-sh-dir (str dir)
        (git "init" "-q")
        (git "-c" "user.name=t" "-c" "user.email=t@example.com" "-c" "commit.gpgsign=false"
             "commit" "-q" "--no-verify" "--allow-empty" "-m" "x")
        (git "checkout" "-q" "--detach")
        (let [pv (bp/collect {:lib lib :version "1.2.3"})]
          (is (= (git "rev-parse" "HEAD") (:commit pv)))
          (is (nil? (:branch pv)))
          (is (false? (:dirty pv)))))
      (finally (fs/delete-tree dir)))))
