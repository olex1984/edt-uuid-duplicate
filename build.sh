#!/usr/bin/env bash
# Сборка jar без PDE: компиляция JDT-компилятором и упаковка.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")" && pwd)
EDT_PLUGINS=${EDT_PLUGINS:-/opt/1C/1CE/components/1c-edt-2026.2.2+21-x86_64/plugins}
JAVA_HOME=${JAVA_HOME:-/opt/1C/1CE/components/axiom-jdk-full-25.0.4+5-x86_64}
BUNDLE=$ROOT/ru.citcer.uuidcheck
OUT=$ROOT/build
rm -rf "$OUT"
mkdir -p "$OUT/classes" "$OUT/jar/ru"
cd "$BUNDLE"
find src -name '*.java' > "$OUT/sources.txt"
"$JAVA_HOME/bin/javac" --release 17 -encoding UTF-8 -d "$OUT/classes" \
    -cp "$EDT_PLUGINS/*" @"$OUT/sources.txt"
cp -r "$OUT/classes/ru" "$OUT/jar/"
cp -r icons "$OUT/jar/"
cp plugin.xml plugin.properties "$OUT/jar/"
cp src/ru/citcer/uuidcheck/messages.properties "$OUT/jar/ru/citcer/uuidcheck/"
cd "$OUT/jar"
"$JAVA_HOME/bin/jar" cfm "$OUT/ru.citcer.uuidcheck_1.0.0.jar" \
    "$BUNDLE/META-INF/MANIFEST.MF" .
echo "Built: $OUT/ru.citcer.uuidcheck_1.0.0.jar"
