#!/bin/sh
set -eu

KOTLIN_HOME=${KOTLIN_HOME:?Set KOTLIN_HOME to a Kotlin compiler distribution}
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd -P)
OUT=${TMPDIR:-/tmp}/moataz-vid-smoke.jar
COROUTINES=$KOTLIN_HOME/lib/kotlinx-coroutines-core-jvm.jar

find "$ROOT/core-model/src/main/kotlin" \
     "$ROOT/storage-core/src/main/kotlin" \
     "$ROOT/media-engine/src/main/kotlin" \
     "$ROOT/tools/smoke-tests/src" \
     -name '*.kt' -print > "${OUT}.sources"

"$KOTLIN_HOME/bin/kotlinc" @"${OUT}.sources" -cp "$COROUTINES" -include-runtime -d "$OUT"
java -cp "$OUT:$COROUTINES" MainKt

