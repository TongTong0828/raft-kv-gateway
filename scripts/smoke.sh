#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
test -f compose.yaml || { echo 'Missing Compose stack implementation' >&2; exit 1; }
./scripts/dev.sh seed
docker compose run --rm -T cli -jar /app/integration-tests.jar smoke
