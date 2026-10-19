# Local monotonic k6 generator

This directory patches upstream k6 v2.3.0 commit
`e0887846143ab176d4b5483c9d52cf3b3e009f1a`. This is a locally modified
generator, not an official Grafana image. The patch, its added Go tests and
upstream license are distributed under GNU AGPLv3; see [LICENSE.md](LICENSE.md).
The Java gateway remains a separate program.

Upstream HTTP tracing stores Unix wall timestamps and subtracts them when
calculating response timings. The patch stores nanosecond offsets from one
process monotonic epoch, plus one to retain zero as the unset sentinel.
`Done` snapshots callback timestamps before capturing its endpoint; late
callbacks cannot introduce a timestamp after that endpoint. Wall `EndTime`,
the existing duration components, callback selection, HTTP/2 overlap handling,
connection reuse and units are retained. No values are clamped or filtered.

The deterministic Go tests use hand-derived event offsets and an injected
end clock. They cover wall jumps in both directions, a callback racing the
endpoint, response-before-write ordering, no response, reused connections,
same-tick events and concurrent late callbacks. The existing proxy regression
fixture is converted to the same monotonic domain without changing its claim.

With Python 3.12+, Git and Docker available, run:

```sh
scripts/k6.sh build
scripts/k6.sh provenance
```

`pin.json` fixes the source archive checksum, upstream commit, Go 1.27.1
builder digest, runtime digest and allowed linux/arm64 and linux/amd64 platforms.
The Docker server's native platform is selected by default, including amd64 CI;
`--platform linux/arm64` or `--platform linux/amd64` is an explicit override.
The source includes
its vendor tree. Compilation runs offline with `GOTOOLCHAIN=local`,
`GOPROXY=off`, `CGO_ENABLED=0`, two CPUs and 2 GiB memory. Full httpext tests
run first with the same bounds; upstream `TestDnsResolve` needs DNS, so that
test container has networking while Go dependency fetching stays disabled.
All downloads, expanded sources, caches, logs, binaries and the image manifest
remain under ignored `work/k6/`. Nothing is installed globally.

The output tag is `raft-kv-k6:monotonic`. Consumers must resolve and run the
immutable `imageId` returned by `provenance`, rather than trusting the tag.
Verification checks image labels, platform, embedded provenance, current
patch checksums and the actual binary SHA256. `io.raftkv.k6.*` labels record
upstream commit, source archive SHA256, patch SHA256, binary SHA256, clock and
build origin. Patch SHA256 is over concatenated patch bytes in `pin.json`
order. Individual patch checksums and build flags are embedded as well.

`/usr/share/raft-kv-k6/` contains `provenance.json`, `k6.sha256`, the upstream
license and source/rebuild pointers. Rebuild from this checkout and the
checksum-verified source archive; do not report this image as the official
digest or reuse results from the earlier invalid native-clock run.
