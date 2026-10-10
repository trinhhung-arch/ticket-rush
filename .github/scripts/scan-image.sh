#!/usr/bin/env bash
# Usage: scan-image.sh <image>
# SC-04: Trivy on one container image, its OS packages and the jars or binaries in it. Writes the High and
# Critical CVEs to the job summary and exits 5 when a Critical one has a fixed version to move to.
set -euo pipefail
image=$1
dir=$(mktemp -d)
report=$dir/report.json

# Findings that do not apply, each with its reason and expiry (.trivyignore.yaml at the repository root).
ignore=$(git rev-parse --show-toplevel)/.trivyignore.yaml

docker run --rm -v /var/run/docker.sock:/var/run/docker.sock -v trivy-cache:/root/.cache \
  -v "$ignore:/trivyignore.yaml:ro" "$TRIVY_IMAGE" \
  image --quiet --scanners vuln --severity HIGH,CRITICAL --ignorefile /trivyignore.yaml --format json "$image" > "$report"

fixable=$(jq '[.Results[]?.Vulnerabilities[]? | select(.Severity == "CRITICAL" and (.FixedVersion // "") != "")] | length' "$report")
{
  echo "### Trivy: \`$image\`"
  echo
  echo "Critical with a fix: **$fixable**"
  echo
  echo '```'
  docker run --rm -v "$dir:/report:ro" "$TRIVY_IMAGE" convert --quiet --format table /report/report.json
  echo '```'
} >> "${GITHUB_STEP_SUMMARY:-/dev/stdout}"

[ "$fixable" -eq 0 ] || exit 5
