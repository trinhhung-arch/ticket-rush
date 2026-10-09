#!/usr/bin/env bash
# Usage: gate.sh <scanner> <exit status> [status that means "findings", default 1]
# Decides what a security scanner's exit status does to the job. Findings above the scanner's threshold
# only warn while SECURITY_GATE=report (the security plan's first weeks, to sort out false positives)
# and fail the job once it is "enforce". Any other non-zero status is the scanner itself breaking, which
# always fails: a scan that silently stops running is worse than a red build.
set -euo pipefail
scanner=$1 status=$2 findings=${3:-1}

if [ "$status" -eq 0 ]; then
  exit 0
fi
if [ "$status" -ne "$findings" ]; then
  echo "::error title=$scanner::the scanner failed with exit status $status"
  exit "$status"
fi
if [ "${SECURITY_GATE:-report}" = enforce ]; then
  echo "::error title=$scanner::findings above the threshold; see the job summary"
  exit 1
fi
echo "::warning title=$scanner::findings above the threshold (report only for now); see the job summary"
