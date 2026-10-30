#!/usr/bin/env bash
set -uo pipefail
export STACK_LOG_DIR="${STACK_LOG_DIR:-benchmark/logs/validation}"
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

MVN="${MVN:-mvn}"
export MONGO_DATABASE="${MONGO_DATABASE:-validation}"
export DATALAKE_PATH="${DATALAKE_PATH:-benchmark/tmp/validation/datalake}"
export BOOK_SOURCE="${BOOK_SOURCE:-sample_dataset}"
export REDELIVERY_DELAY_MS="${REDELIVERY_DELAY_MS:-200}"
export INDEXING_WORKERS="${INDEXING_WORKERS:-2}"
export INGESTION_PORT INDEXING_PORT SEARCH_PORT PORT_STEP

REPORT_DIR="$STACK_LOG_DIR/reports"
ONLY="${1:-}"

stop_all() {
    bash scripts/stop.sh
}

windows_path() {
    if command -v cygpath >/dev/null; then cygpath -m "$1"; else echo "$1"; fi
}

rm -rf "$DATALAKE_PATH" "$STACK_LOG_DIR"
mkdir -p "$STACK_LOG_DIR"

log "Building the services"
"$MVN" -q -B -DskipTests package || fail "The build failed"

trap stop_all EXIT
bash scripts/start.sh || fail "The stack did not start"

export STACK_ROOT="$(windows_path "$ROOT")"
export STACK_BASH="$(windows_path "$BASH")"

log "Running the validation tests against database $MONGO_DATABASE"
test_filter=()
if [[ -n "$ONLY" ]]; then
    test_filter=("-Dtest=$ONLY")
fi
"$MVN" -B -Pintegration -pl integration -am test \
    -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false \
    '-Dtest=*IT' "${test_filter[@]}" 2>&1 | tee "$STACK_LOG_DIR/maven.log"
result=${PIPESTATUS[0]}

mkdir -p "$REPORT_DIR"
cp integration/target/surefire-reports/*.txt "$REPORT_DIR" 2>/dev/null || true
grep -E 'Tests run:.*(Failures|Errors)' "$STACK_LOG_DIR/maven.log" | sed 's/\x1b\[[0-9;]*m//g' | tail -12
log "Reports: $REPORT_DIR"
exit "$result"
