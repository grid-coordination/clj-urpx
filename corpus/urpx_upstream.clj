;; SPDX-FileCopyrightText: 2026 Clark Communications Corporation
;; SPDX-License-Identifier: MIT
(ns urpx-upstream
  "The URPX standard's own test cases in a local checkout of urpx-org/urpx,
  walked as corpus/urpx.corpus.json declares them.

  They exercise shapes a single publisher's filings may not, including entities
  this library does not model, so load them with `urpx.load/scan` rather than
  `documents`: what it discards is the interesting half."
  (:require [clojure.java.io :as io]
            [corpus-json]))

(defn sources
  "Every test-case document under `root`, as Files, in C-locale byte order."
  [root]
  (corpus-json/sources root (corpus-json/read-layout (io/resource "urpx.corpus.json"))))
