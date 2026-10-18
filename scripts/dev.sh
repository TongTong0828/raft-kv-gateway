#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
case "${1:-}" in
  build) docker compose build --build-arg "VCS_REF=$(git rev-parse HEAD)" cp1 ;;
  up) docker compose up -d --wait --wait-timeout 120 ;;
  seed)
    revision=$(./scripts/cli.sh service get orders 2>/dev/null | sed -n 's/.*"revision":"\([0-9][0-9]*\)".*/\1/p') || revision=0
    revision=${revision:-0}
    ./scripts/cli.sh service put /workspace/examples/orders.json --expected-revision "$revision"
    ;;
  smoke) ./scripts/smoke.sh ;;
  down) docker compose down ;;
  reset) docker compose down --volumes ;;
  *) echo 'Usage: scripts/dev.sh build|up|seed|smoke|down|reset' >&2; exit 2 ;;
esac
