(ns urpx.provenance-test
  "Smoke test for the build-provenance instrumentation in build.clj.

   The build pipeline (`clojure -T:build ci`) writes
   META-INF/energy.grid-coordination/clj-urpx/build-provenance.{edn,json}
   into `target/classes/`. This test reads the .edn directly from the
   filesystem so it doesn't require `target/classes` on the test classpath.

   When the build hasn't run yet (fresh checkout, `clojure -M:test`
   without `ci` first), the file is absent and the test passes trivially —
   we don't want to block the test suite on a build artifact, only catch
   regressions when the build *did* run but instrumentation broke."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]))

(def ^:private provenance-edn
  (io/file "target/classes/META-INF/energy.grid-coordination/clj-urpx/build-provenance.edn"))

(deftest provenance-embedded-by-build
  (if-not (.exists provenance-edn)
    (testing "build hasn't run; nothing to verify"
      (is true "skipped — run `clojure -T:build ci` to populate target/classes"))
    (let [pv (edn/read-string (slurp provenance-edn))]
      (testing "build-provenance.edn carries the expected fields"
        (is (= "energy.grid-coordination/clj-urpx" (:artifact pv)))
        (is (string? (:version pv)))
        (is (string? (:commit pv)) "commit must be present and a string")
        (is (string? (:commit-abbreviated pv)))
        (is (string? (:branch pv)))
        (is (string? (:describe pv)))
        (is (contains? pv :dirty) "dirty flag must be present")))))
