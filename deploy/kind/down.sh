#!/usr/bin/env bash
# Deletes the kind cluster created by up.sh, with all its data.
set -euo pipefail
kind delete cluster --name ticketrush
