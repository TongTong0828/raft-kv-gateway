#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p work/tools
TASK_FORMAT_URL=https://repo.maven.apache.org/maven2/com/google/googlejavaformat/google-java-format/1.25.2/google-java-format-1.25.2-all-deps.jar
if [ ! -f work/tools/google-java-format.jar ]; then curl -fLsS "$TASK_FORMAT_URL" -o work/tools/google-java-format.jar; fi
git ls-files --cached --others --exclude-standard '*.java' > work/java-sources.args
docker run --rm -v "$PWD:/workspace" -w /workspace maven:3.9.16-eclipse-temurin-21-noble@sha256:99e61abcff91a9b1333463bd8451fb18495d6eba9250ac66a338b518f8278320 \
  xargs -a work/java-sources.args java -jar work/tools/google-java-format.jar "${@:---replace}"
