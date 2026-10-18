#!/usr/bin/env bash
set -euo pipefail
TASK_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
IMAGE='maven:3.9.16-eclipse-temurin-21-noble@sha256:99e61abcff91a9b1333463bd8451fb18495d6eba9250ac66a338b518f8278320'
exec docker run --rm --memory=2g --cpus=4 -e MAVEN_OPTS=-Xmx768m -v "$TASK_ROOT:/workspace" -v raft-kv-gateway-m2:/m2 -w /workspace "$IMAGE" mvn -B -ntp -Dmaven.repo.local=/m2/repository "$@"
