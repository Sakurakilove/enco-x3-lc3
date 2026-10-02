#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
for tool in javac jar d8 aapt apksigner python; do
 command -v "$tool" >/dev/null || { echo "Missing build tool: $tool" >&2; exit 1; }
done
for api in lib/android.jar lib/xposed-api.jar; do
 [ -f "$api" ] || { echo 'Run bash tools/fetch-build-libs.sh first.' >&2; exit 1; }
done
rm -rf build/classes build/dex
mkdir -p build/classes build/dex
javac -source 8 -target 8 -cp lib/android.jar:lib/xposed-api.jar -d build/classes src/local/enco/lc3/*.java
jar cf build/code.jar -C build/classes .
d8 --min-api 27 --lib lib/android.jar --classpath lib/xposed-api.jar --output build/dex build/code.jar
aapt package -f -M AndroidManifest.xml -S res -A assets -I lib/android.jar -F build/unsigned.apk
python - <<'PY'
from zipfile import ZipFile
with ZipFile('build/unsigned.apk', 'a') as archive:
    archive.write('build/dex/classes.dex', 'classes.dex')
PY
ENCO_SIGNING_KEYSTORE=${ENCO_SIGNING_KEYSTORE:-signing.p12}
ENCO_SIGNING_PASSWORD=${ENCO_SIGNING_PASSWORD:-localtest}
export ENCO_SIGNING_PASSWORD
if [ ! -f "$ENCO_SIGNING_KEYSTORE" ]; then
 keytool -genkeypair -keystore "$ENCO_SIGNING_KEYSTORE" -storepass:env ENCO_SIGNING_PASSWORD -keypass:env ENCO_SIGNING_PASSWORD -alias local -dname 'CN=Local Enco LC3 Build' -keyalg RSA -keysize 2048 -validity 3650
 chmod 600 "$ENCO_SIGNING_KEYSTORE"
fi
apksigner sign --ks "$ENCO_SIGNING_KEYSTORE" --ks-pass env:ENCO_SIGNING_PASSWORD --out build/Enco-X3-LC3.apk build/unsigned.apk
apksigner verify --verbose build/Enco-X3-LC3.apk
