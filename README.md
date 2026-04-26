# clj-urpx

Clojure library for [URPX](https://lfenergy.org/projects/utility-rate-plan-exchange-urpx/) (Utility Rate Plan Exchange) — parse, validate, and resolve prices from URPX rate plan documents (JSON-LD).

## What is URPX?

URPX is an [LF Energy](https://lfenergy.org/) open standard for exchanging utility rate plan information using semantic web technologies (RDF, OWL, SHACL, JSON-LD). It enables precise, machine-readable data exchange between utilities, regulators, vendors, and researchers.

## What does this library do?

- **Parse** URPX JSON-LD rate plan documents into Clojure data structures
- **Resolve** prices for any given timestamp and timezone (TOU, tiered, flat, seasonal)
- **Generate** contiguous price interval schedules for OpenADR 3 events

## Status

Early development. See the [URPX specification](https://github.com/urpx-org/urpx) for the ontology and test cases.

## License

Copyright (c) 2026 Clark Communications Corporation. MIT License.
