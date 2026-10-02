#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p lib
fetch() {
 local name=$1 url=$2 expected=$3
 curl --fail --location --retry 2 --connect-timeout 15 --max-time 120 "$url" -o "lib/$name.tmp"
 printf '%s  %s\n' "$expected" "lib/$name.tmp" | sha256sum --check --status
 mv "lib/$name.tmp" "lib/$name"
}
fetch android.jar https://repo.maven.apache.org/maven2/com/google/android/android/4.1.1.4/android-4.1.1.4.jar 84072541cbb711eff89f7277100ff854929a446dba7ceb1b195c340e0b4fd3cb
fetch xposed-api.jar https://api.xposed.info/de/robv/android/xposed/api/82/api-82.jar f48c635f1c7469fdec0e00ad2ea0b7a6b2f5b55065784a35b7ca3a84615e8e25
printf 'Verified compile-only Android and Xposed API jars.\n'
