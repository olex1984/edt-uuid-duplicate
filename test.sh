#!/usr/bin/env bash
# Компиляция и запуск JUnit-тестов (парсер + сверка с эталонными экспортами).
set -euo pipefail
ROOT=$(cd "$(dirname "$0")" && pwd)
EDT_PLUGINS=${EDT_PLUGINS:-/opt/1C/1CE/components/1c-edt-2026.2.2+21-x86_64/plugins}
JAVA_HOME=${JAVA_HOME:-/opt/1C/1CE/components/axiom-jdk-full-25.0.4+5-x86_64}
"$ROOT/build.sh" >/dev/null
BUNDLE_CLASSES=$ROOT/build/classes
TESTS=$ROOT/ru.citcer.uuidcheck.tests
rm -rf "$TESTS/bin" && mkdir -p "$TESTS/bin"
cd "$TESTS"
find src -name '*.java' > bin/sources.txt
"$JAVA_HOME/bin/javac" --release 17 -encoding UTF-8 -d bin \
    -cp "$BUNDLE_CLASSES:$EDT_PLUGINS/*" @bin/sources.txt
"$JAVA_HOME/bin/java" -cp "bin:$BUNDLE_CLASSES:$EDT_PLUGINS/*" \
    org.junit.runner.JUnitCore \
    ru.citcer.uuidcheck.tests.UuidXmlParserTest \
    ru.citcer.uuidcheck.tests.ParityTest
