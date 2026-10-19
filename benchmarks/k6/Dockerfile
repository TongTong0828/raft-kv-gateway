# This is a locally patched k6, not an official Grafana release.
FROM grafana/k6:2.3.0@sha256:9c2dee7f8ed74d317e4027c06a10f169b625638189de8d4555d0b3486a5aeb34
ARG UPSTREAM_COMMIT
ARG SOURCE_SHA256
ARG PATCH_SHA256
ARG BINARY_SHA256
ARG K6_PLATFORM
LABEL org.opencontainers.image.title="k6 with local monotonic HTTP tracer" \
      org.opencontainers.image.version="2.3.0-monotonic.1" \
      org.opencontainers.image.source="https://github.com/grafana/k6" \
      org.opencontainers.image.revision="$UPSTREAM_COMMIT" \
      org.opencontainers.image.licenses="AGPL-3.0" \
      io.raftkv.k6.upstream.commit="$UPSTREAM_COMMIT" \
      io.raftkv.k6.source.sha256="$SOURCE_SHA256" \
      io.raftkv.k6.patch.sha256="$PATCH_SHA256" \
      io.raftkv.k6.binary.sha256="$BINARY_SHA256" \
      io.raftkv.k6.platform="$K6_PLATFORM" \
      io.raftkv.k6.clock="process-monotonic-v1" \
      io.raftkv.k6.build-origin="raft-kv-gateway-monotonic.1"
COPY k6 /usr/bin/k6
COPY k6.sha256 provenance.json LICENSE.md SOURCE-REBUILD.txt /usr/share/raft-kv-k6/
