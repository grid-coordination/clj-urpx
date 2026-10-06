(ns hooks.urpx.defs)

(defmacro defurpx
  "Lint-time expansion of urpx.defs/defurpx: the kind, then a plain def."
  [kind name & body]
  `(do ~kind
       (def ~name ~@body)))
