#!/usr/bin/env bash
# Renders every ```mermaid block of the repository's Markdown files with mermaid-cli, so a diagram GitHub cannot render
# fails here (locally and in CI) instead of showing up as an error box after the merge.
#   scripts/check-mermaid.sh            # every tracked *.md file that contains a mermaid block
#   scripts/check-mermaid.sh FILE...    # only these files
# Needs Node. MMDC overrides the mermaid-cli command (default: npx with a pinned version, which downloads it and a
# headless Chromium on first use). PUPPETEER_NO_SANDBOX=1 (CI) starts Chromium with --no-sandbox.
set -euo pipefail

MERMAID_CLI_VERSION=11.17.0
cd "$(dirname "$0")/.."

if [ "$#" -gt 0 ]; then files=("$@"); else
  files=()
  while IFS= read -r f; do files+=("$f"); done < <(git ls-files -z -co --exclude-standard '*.md' | xargs -0 grep -l '^```mermaid' || true)
fi
if [ "${#files[@]}" -eq 0 ]; then echo "mermaid: no diagrams found"; exit 0; fi

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
if [ "${PUPPETEER_NO_SANDBOX:-}" = 1 ]; then
  echo '{"args":["--no-sandbox","--disable-setuid-sandbox"]}' >"$work/puppeteer.json"
else
  echo '{}' >"$work/puppeteer.json"
fi

read -r -a mmdc <<<"${MMDC:-npx --yes @mermaid-js/mermaid-cli@$MERMAID_CLI_VERSION}"
blocks=0 failed=0 n=0
for f in "${files[@]}"; do
  n=$((n + 1))
  count=$(grep -c '^```mermaid' "$f" || true)
  blocks=$((blocks + count))
  # A Markdown input renders each block to an SVG and fails on the first diagram that does not parse.
  if ! "${mmdc[@]}" --quiet -p "$work/puppeteer.json" -i "$f" -o "$work/$n.md" >"$work/$n.log" 2>&1; then
    failed=$((failed + 1))
    echo "FAIL  $f ($count diagrams)"
    grep -v '^\s*at ' "$work/$n.log" | sed 's/^/      /' | head -15
  fi
done

if [ "$failed" -gt 0 ]; then
  echo "mermaid: $failed of ${#files[@]} files have a diagram that does not render"
  exit 1
fi
echo "mermaid: $blocks diagrams in ${#files[@]} files render"
