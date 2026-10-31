#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

BENCH_XMX="${BENCH_XMX:-4G}"
BENCH_FORKS="${BENCH_FORKS:-1}"
BENCH_WARMUP="${BENCH_WARMUP:-5}"
BENCH_ITERATIONS="${BENCH_ITERATIONS:-10}"
BENCH_THREADS="${BENCH_THREADS:-1,2,4,8}"
BENCH_THREADS_INDEX="${BENCH_THREADS_INDEX:-1000}"
BENCH_INDEX_SIZES="${BENCH_INDEX_SIZES:-100,500,1000,2000}"
BENCH_ONLY="${BENCH_ONLY:-}"
BENCH_KEEP_DB="${BENCH_KEEP_DB:-false}"
PYTHON="${PYTHON:-python}"
MVN="${MVN:-mvn}"
MONGO_HOST_PORT="${MONGO_HOST_PORT:-localhost:27017}"
BROKER_HOST_PORT="${BROKER_HOST_PORT:-localhost:61616}"

COMMON_JAR="common/target/common-1.0.0-benchmarks.jar"
INDEXING_JAR="indexing-service/target/indexing-service-1.0.0-benchmarks.jar"
SEARCH_JAR="search-service/target/search-service-1.0.0-benchmarks.jar"

log() {
    printf '[benchmark] %s\n' "$*"
}

fail() {
    printf '[benchmark] ERROR: %s\n' "$*" >&2
    exit 1
}

usage() {
    cat <<'EOF'
Usage: benchmark/run.sh <command>

  dataset      download the benchmark books once into benchmark/cache
  micro        run every JMH benchmark with the guide settings
  smoke        run every JMH benchmark once, only to check that it works
  drop-db      drop the bench_* databases
EOF
}

port_open() {
    local host="${1%:*}" port="${1##*:}"
    (exec 3<>"/dev/tcp/$host/$port") 2>/dev/null
}

require_tools() {
    command -v java >/dev/null || fail "java not found: set JAVA_HOME and add it to PATH"
    "$PYTHON" -c "import psutil" 2>/dev/null || fail "psutil not found: run $PYTHON -m pip install -r benchmark/requirements.txt"
}

build_jars() {
    if [[ -f "$COMMON_JAR" && -f "$INDEXING_JAR" && "${BENCH_REBUILD:-false}" != "true" ]]; then
        return
    fi
    [[ -e "$MVN" ]] || command -v "$MVN" >/dev/null || fail "Maven not found: set MVN to the mvn command"
    log "Building the benchmark jars"
    "$MVN" -q -B -Pbenchmark -DskipTests package
}

require_dataset() {
    "$PYTHON" -I benchmark/dataset.py --check || fail "The book cache is incomplete: run benchmark/run.sh dataset"
}

jmh() {
    local experiment="$1" jar="$2"
    shift 2
    if [[ -n "$BENCH_ONLY" && ! "$experiment" =~ $BENCH_ONLY ]]; then
        log "Skipping $experiment"
        return
    fi
    local json="$RAW_DIR/$experiment.json"
    log "Running $experiment"
    java -jar "$jar" "$@" \
        -wi "$WARMUP" -i "$ITERATIONS" -f "$BENCH_FORKS" \
        -jvmArgs "-Xms$BENCH_XMX -Xmx$BENCH_XMX" \
        -rf json -rff "$json" \
        2>&1 | tee "$LOG_DIR/$experiment.log"
    "$PYTHON" benchmark/jmh_to_csv.py "$json" "$RUN_DIR/$experiment.csv" \
        --run-id "$RUN_ID" --experiment "$experiment" --xmx "$BENCH_XMX" --smoke "$SMOKE"
}

drop_databases() {
    if [[ "$BENCH_KEEP_DB" == "true" ]]; then
        log "Keeping the bench_* databases"
    elif [[ -f "$INDEXING_JAR" ]] && port_open "$MONGO_HOST_PORT"; then
        java -cp "$INDEXING_JAR" bdsm.indexing.benchmark.BenchmarkDatabases drop
    fi
}

start_run() {
    RUN_ID="$(date +%Y%m%d-%H%M%S)-$1"
    RUN_DIR="benchmark/results/$RUN_ID"
    RAW_DIR="$RUN_DIR/raw"
    LOG_DIR="benchmark/logs/$RUN_ID"
    mkdir -p "$RAW_DIR" "$LOG_DIR"
    if port_open "$MONGO_HOST_PORT"; then
        BENCH_MONGO_VERSION="$(java -cp "$INDEXING_JAR" bdsm.indexing.benchmark.BenchmarkDatabases version 2>/dev/null | tail -1)"
        export BENCH_MONGO_VERSION
    fi
    export BENCH_XMX BENCH_FORKS BENCH_THREADS BENCH_THREADS_INDEX BENCH_INDEX_SIZES
    "$PYTHON" benchmark/run_info.py "$RUN_DIR/run.json" "$RUN_ID" "$1"
    log "Results go to $RUN_DIR"
}

run_cpu_benchmarks() {
    jmh tokenizer "$COMMON_JAR" 'TokenizerBenchmark' -t 1 $TIMING
    jmh metadata "$INDEXING_JAR" 'MetadataExtractorBenchmark' -t 1 $TIMING
    jmh eventbus-embedded "$COMMON_JAR" 'EventBusBenchmark' -p broker=embedded -p consumers=1,2,4 $TIMING
    if port_open "$BROKER_HOST_PORT"; then
        jmh eventbus-external "$COMMON_JAR" 'EventBusBenchmark' -p broker=external -p consumers=1,2,4 $TIMING
    else
        log "No broker on $BROKER_HOST_PORT: skipping eventbus-external"
    fi
}

run_mongo_benchmarks() {
    if ! port_open "$MONGO_HOST_PORT"; then
        log "No MongoDB on $MONGO_HOST_PORT: skipping the MongoDB benchmarks"
        return
    fi
    trap drop_databases EXIT
    local sizes
    sizes="$(printf '%s,%s' "$BENCH_INDEX_SIZES" "$BENCH_THREADS_INDEX" | tr ',' '\n' | sort -nu | tr '\n' ' ')"
    log "Building the base indexes: $sizes"
    java "-Xmx$BENCH_XMX" -cp "$INDEXING_JAR" bdsm.indexing.benchmark.BenchmarkDatabases build $sizes
    jmh addtoset "$INDEXING_JAR" 'AddToSetBenchmark' $TIMING
    jmh index-growth "$INDEXING_JAR" 'IndexingBatchBenchmark.updateDatamart' -p threads=1 -p "indexSize=$BENCH_INDEX_SIZES"
    jmh index-threads "$INDEXING_JAR" 'IndexingBatchBenchmark.updateDatamart' -p "threads=$BENCH_THREADS" -p "indexSize=$BENCH_THREADS_INDEX"
    jmh index-handler "$INDEXING_JAR" 'IndexingBatchBenchmark.handleBookDownloaded' -p "threads=$BENCH_THREADS" -p "indexSize=$BENCH_THREADS_INDEX"
    if [[ -f "$SEARCH_JAR" ]]; then
        jmh search "$SEARCH_JAR" 'SearchBenchmark' $TIMING
    else
        log "No search benchmarks in this build: skipping search"
    fi
}

run_micro() {
    require_tools
    require_dataset
    build_jars
    SMOKE=false
    WARMUP="$BENCH_WARMUP"
    ITERATIONS="$BENCH_ITERATIONS"
    TIMING=""
    start_run micro
    run_cpu_benchmarks
    run_mongo_benchmarks
    log "Done: $RUN_DIR"
}

run_smoke() {
    require_tools
    require_dataset
    build_jars
    SMOKE=true
    WARMUP=0
    ITERATIONS=1
    TIMING="-r 1s"
    BENCH_FORKS=1
    BENCH_INDEX_SIZES=100
    BENCH_THREADS=1,4
    BENCH_THREADS_INDEX=100
    start_run smoke
    run_cpu_benchmarks
    run_mongo_benchmarks
    log "Done: $RUN_DIR"
}

case "${1:-}" in
    dataset) "$PYTHON" -I benchmark/dataset.py ;;
    micro) run_micro ;;
    smoke) run_smoke ;;
    drop-db) drop_databases ;;
    *) usage; exit 1 ;;
esac
