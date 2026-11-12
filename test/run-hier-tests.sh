#!/bin/bash
# Resolve source names before booting the portal; never include contentmigration tests.
set -euo pipefail
MODULE_DIR=$(cd -- "$(dirname -- "$0")/.." && pwd)
SOURCE_DIR="$MODULE_DIR/test/java"
PACKAGE=br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.test
TEST_DIR="$SOURCE_DIR/${PACKAGE//.//}"
CLASSES=()
fail() { echo "[run-hier-tests] ERROR: $*" >&2; exit 2; }
if [ "$#" -eq 0 ]; then
    mapfile -t sources < <(find "$TEST_DIR" -name '*Test.java' ! -name UiScenarioTest.java ! -name NoLeftoverDataTest.java | sort)
    sources+=("$TEST_DIR/support/NoLeftoverDataTest.java")
else
    sources=()
    for name in "$@"; do
        if [[ "$name" == "$PACKAGE".* ]]; then
            file="$SOURCE_DIR/${name//.//}.java"
            [ -f "$file" ] || fail "classe inexistente: $name"
        elif [[ "$name" != *.* && "$name" =~ ^[A-Za-z_][A-Za-z_0-9]*$ ]]; then
            mapfile -t matches < <(find "$TEST_DIR" -name "$name.java")
            [ "${#matches[@]}" -eq 1 ] || fail "esperada uma classe $name, encontradas ${#matches[@]}"
            file=${matches[0]}
        else
            fail "classe fora do pacote: $name"
        fi
        sources+=("$file")
    done
fi
for file in "${sources[@]}"; do
    class=${file#"$SOURCE_DIR/"}
    class=${class%.java}
    CLASSES+=("${class//\//.}")
done
[ "${#CLASSES[@]}" -gt 0 ] || fail "nenhuma classe encontrada"
echo "[run-hier-tests] PROGRESS classes: ${CLASSES[*]}"
exec bash "$MODULE_DIR/test/run-tests.sh" "${CLASSES[@]}"
