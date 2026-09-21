#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Clark Communications Corporation
# SPDX-License-Identifier: MIT
#
# Re-sync the vendored URPX test fixtures under
# test/resources/urpx-fixtures/ from a local urpx-rate-plans checkout.
#
# Usage: scripts/sync-urpx-fixtures.sh <path-to-urpx-rate-plans-checkout>
#
# Each fixture is fetched at its pinned upstream commit via
# `git show <commit>:<path>` so the working tree of the urpx-rate-plans
# checkout does not affect the result.
#
# When you bump a pinned commit here, also update
# test/resources/urpx-fixtures/FIXTURES.md so the audit trail tracks.

set -euo pipefail

if [[ $# -ne 1 ]]; then
  echo "usage: $0 <path-to-urpx-rate-plans-checkout>" >&2
  exit 2
fi

src_dir=$1
if [[ ! -d "$src_dir/.git" ]]; then
  echo "error: $src_dir is not a git checkout" >&2
  exit 2
fi

repo_root=$(cd "$(dirname "$0")/.." && pwd)
dest=$repo_root/test/resources/urpx-fixtures

# fixture-name                   upstream-commit  upstream-path
fixtures=(
  "pge-e-1-2026-03-01             1e4ad46  us/ca/pge/electricity/e-1/versions/2026-03-01/rate-plan_pge_e-1_2026-03-01.jsonld"
  "pge-e-tou-c-2026-03-01         1e4ad46  us/ca/pge/electricity/e-tou-c/versions/2026-03-01/rate-plan_pge_e-tou-c_2026-03-01.jsonld"
  "pge-e-tou-d-2026-03-27         1e4ad46  us/ca/pge/electricity/e-tou-d/versions/2026-03-27/rate-plan_pge_e-tou-d_2026-03-27.jsonld"
  "pge-ev2-2026-03-27             1e4ad46  us/ca/pge/electricity/ev2/versions/2026-03-27/rate-plan_pge_ev2_2026-03-27.jsonld"

  # LADWP, migration wave 1, URPX v0.5.1. Vendored ALONGSIDE the v0.2.x PG&E
  # set rather than replacing it: the corpus is mixed-vocabulary for the whole
  # migration, so the suite should exercise both at once. Three of the five are
  # RatePlanModifiers, and NEM is export-direction rather than a discount.
  "ladwp-r-1a-2026-04-01          6453536  us/ca/ladwp/electricity/r-1a/versions/2026-04-01/rate-plan_ladwp_r-1a_2026-04-01.jsonld"
  "ladwp-r-1b-2026-04-01          6453536  us/ca/ladwp/electricity/r-1b/versions/2026-04-01/rate-plan_ladwp_r-1b_2026-04-01.jsonld"
  "ladwp-ez-save-2008-09-01       6453536  us/ca/ladwp/electricity/ez-save/versions/2008-09-01/rate-plan-modifier_ladwp_ez-save_2008-09-01.jsonld"
  "ladwp-lifeline-2008-09-01      6453536  us/ca/ladwp/electricity/lifeline/versions/2008-09-01/rate-plan-modifier_ladwp_lifeline_2008-09-01.jsonld"
  "ladwp-nem-2008-09-01           6453536  us/ca/ladwp/electricity/nem/versions/2008-09-01/rate-plan-modifier_ladwp_nem_2008-09-01.jsonld"

  # SCE and SDG&E, URPX v0.5.1, the first percentage discounts in the corpus:
  # a urpx:Price carrying urpx:multiplier over a urpx:PriceSummary instead of a
  # urpx:unitPrice. Both utilities are vendored because neither alone shows the
  # variation. SCE shares ONE subtotal between CARE and FERA, which differ only
  # in rate; SDG&E carries TWO subtotals on one base plan with different member
  # lists, because Schedule E-CARE excludes applicable exemptions and FERA
  # grants none. Each base plan is a separate filing from its modifiers here,
  # unlike the PG&E @graph bundles, so the cross-document dereference is
  # exercised the way the corpus actually files it.
  "sce-d-2026-03-20               384ffb1  us/ca/sce/electricity/d/versions/2026-03-20/rate-plan_sce_d_2026-03-20.jsonld"
  "sce-care-2026-03-20            384ffb1  us/ca/sce/electricity/care/versions/2026-03-20/rate-plan-modifier_sce_care_2026-03-20.jsonld"
  "sce-fera-2026-03-20            384ffb1  us/ca/sce/electricity/fera/versions/2026-03-20/rate-plan-modifier_sce_fera_2026-03-20.jsonld"
  "sdge-dr-2026-04-01             384ffb1  us/ca/sdge/electricity/dr/versions/2026-04-01/rate-plan_sdge_dr_2026-04-01.jsonld"
  "sdge-care-2025-10-01           384ffb1  us/ca/sdge/electricity/care/versions/2025-10-01/rate-plan-modifier_sdge_care_2025-10-01.jsonld"
  "sdge-fera-2026-04-01           384ffb1  us/ca/sdge/electricity/fera/versions/2026-04-01/rate-plan-modifier_sdge_fera_2026-04-01.jsonld"

  # CPAU E-14, street lighting: the only filing in the corpus with NO energy
  # ledger at all. Flat per-lamp and per-fixture monthly charges, so there is
  # genuinely no marginal per-kWh rate to report, which isolates the
  # zero-energy-ledger case with no other variable. Pinned after 28390dc, where
  # its three ledgers gained the urpx:referencesMetricInput they had been
  # missing; the reported value did not change, because the metric inputs are
  # fixture counts with no measurement kind.
  "cpau-e-14-2025-07-01           28390dc  us/ca/cpau/electricity/e-14/versions/2025-07-01/rate-plan_cpau_e-14_2025-07-01.jsonld"
)

for entry in "${fixtures[@]}"; do
  read -r name commit path <<<"$entry"
  echo "syncing $name ($commit)"
  if ! git -C "$src_dir" cat-file -e "$commit" 2>/dev/null; then
    echo "  error: commit $commit not present in $src_dir — fetch the upstream branch first" >&2
    exit 1
  fi
  git -C "$src_dir" show "$commit:$path" > "$dest/$name.jsonld"
done

echo
echo "synced ${#fixtures[@]} fixture(s) into $dest"
echo "review with: git -C $repo_root diff -- $dest"
