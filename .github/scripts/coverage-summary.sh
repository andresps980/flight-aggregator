#!/usr/bin/env bash
# Prints a Markdown coverage summary from a JaCoCo CSV report.
# Usage: coverage-summary.sh <module>/target/site/jacoco/jacoco.csv [module-label]
set -euo pipefail

csv="${1:-flight-aggregator/target/site/jacoco/jacoco.csv}"
label="${2:-}"

if [ ! -f "$csv" ]; then
  echo "### Coverage${label:+ — $label}"
  echo
  echo "_No JaCoCo report found (the build probably failed before the report was generated)._"
  exit 0
fi

# CSV columns: GROUP,PACKAGE,CLASS,INSTRUCTION_MISSED,INSTRUCTION_COVERED,BRANCH_MISSED,BRANCH_COVERED,
#              LINE_MISSED,LINE_COVERED,COMPLEXITY_MISSED,COMPLEXITY_COVERED,METHOD_MISSED,METHOD_COVERED
awk -F, -v label="$label" '
  NR > 1 {
    im += $4; ic += $5; bm += $6; bc += $7; lm += $8; lc += $9; mm += $12; mc += $13
  }
  function pct(c, m) { return (c + m) == 0 ? "n/a" : sprintf("%.1f%%", 100 * c / (c + m)) }
  END {
    print "### Coverage" (label == "" ? "" : " — " label) " (unit + integration, JaCoCo)"
    print ""
    print "| Counter | Covered | Total | Coverage |"
    print "|---|---:|---:|---:|"
    printf "| Lines | %d | %d | %s |\n", lc, lc + lm, pct(lc, lm)
    printf "| Branches | %d | %d | %s |\n", bc, bc + bm, pct(bc, bm)
    printf "| Instructions | %d | %d | %s |\n", ic, ic + im, pct(ic, im)
    printf "| Methods | %d | %d | %s |\n", mc, mc + mm, pct(mc, mm)
    print ""
    print "_Gate per module: 80% lines, 70% branches. Full HTML report in the `jacoco-report` artifact._"
  }
' "$csv"
