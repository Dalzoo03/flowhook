#!/usr/bin/env bash
set -euo pipefail

project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# macOS 기본 Java를 변경하지 않고 이 명령에서만 Java 21을 사용한다.
if [[ "$(uname -s)" == "Darwin" ]]; then
    export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
    export PATH="$JAVA_HOME/bin:$PATH"
fi

if [[ $# -eq 0 ]]; then
    set -- bootRun
fi

cd "$project_root/backend"
exec ./gradlew --no-daemon "$@"
