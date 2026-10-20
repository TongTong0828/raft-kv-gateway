# kind image identity mapping

The six original kind Pod image IDs are the digest of an imported outer OCI index. This post-run import probe retrieved that exact immutable content from containerd in a temporary kind node, then deleted the node. The outer index points at the measured Docker index, which selects the ARM64 manifest and image config with the recorded source revision. Each raw file below is retained byte-for-byte and its SHA256 is independently validated in `mapping.json`; the package manifest covers them too.

The initial capture helper assumed a manifest and stopped at a config lookup after capture. That parser error and cleanup are retained; offline validation follows the actual index structure and passes. No application or benchmark was rerun or relabeled.
