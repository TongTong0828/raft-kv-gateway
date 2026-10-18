#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
./scripts/k8s-tools.sh
python3 scripts/kind_smoke.py "$@"
