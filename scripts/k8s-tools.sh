#!/usr/bin/env bash
# Pinned, task-local binaries. Does not install into PATH or alter kubeconfig.
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p work/tools
case "$(uname -s)" in Darwin) TASK_OS=darwin;; Linux) TASK_OS=linux;; *) exit 2;; esac
case "$(uname -m)" in arm64|aarch64) TASK_ARCH=arm64;; x86_64) TASK_ARCH=amd64;; *) exit 2;; esac
for TASK_NAME in kind kubectl; do
  if [ "$TASK_NAME" = kind ]; then
    TASK_URL="https://github.com/kubernetes-sigs/kind/releases/download/v0.33.0/kind-$TASK_OS-$TASK_ARCH"
    TASK_SUM_URL="$TASK_URL.sha256sum"
  else
    TASK_URL="https://dl.k8s.io/release/v1.37.0/bin/$TASK_OS/$TASK_ARCH/kubectl"
    TASK_SUM_URL="$TASK_URL.sha256"
  fi
  curl -fLsS "$TASK_SUM_URL" -o "work/tools/$TASK_NAME.expected"
  if ! python3 - "$TASK_NAME" <<'PY'
import hashlib,pathlib,sys
p=pathlib.Path('work/tools')/sys.argv[1]
assert p.is_file() and hashlib.sha256(p.read_bytes()).hexdigest()==p.with_suffix('.expected').read_text().split()[0]
PY
  then curl -fLsS "$TASK_URL" -o "work/tools/$TASK_NAME"; fi
  python3 - "$TASK_NAME" <<'PY'
import hashlib,pathlib,sys
p=pathlib.Path('work/tools')/sys.argv[1]
assert hashlib.sha256(p.read_bytes()).hexdigest()==p.with_suffix('.expected').read_text().split()[0]
p.chmod(0o755)
PY
done
