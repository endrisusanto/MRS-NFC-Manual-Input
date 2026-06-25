#!/usr/bin/env bash
set -euo pipefail

if [[ -z "${JAVA_HOME:-}" && -d /usr/lib/jvm/java-17-openjdk-amd64 ]]; then
  export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
fi

export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$PWD/.gradle}"
exec ./android/gradlew -p android assembleDebug
