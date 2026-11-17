#!/usr/bin/env bash
set -euo pipefail
project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
if [[ $# -gt 2 ]]; then
    echo '사용법: ./scripts/receiver.sh [port=9090] [delayMillis=8000]' >&2
    exit 2
fi
exec "$project_root/scripts/backend.sh" receiver "-PreceiverPort=${1:-9090}" "-PreceiverDelayMillis=${2:-8000}"
