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
BENCH_REPEATS="${BENCH_REPEATS:-3}"
BENCH_PIPELINE_BOOKS="${BENCH_PIPELINE_BOOKS:-200}"
BENCH_PIPELINE_WORKERS="${BENCH_PIPELINE_WORKERS:-2 4 8}"
BENCH_PIPELINE_REPLICAS="${BENCH_PIPELINE_REPLICAS:-2 4}"
BENCH_PIPELINE_PREFETCH="${BENCH_PIPELINE_PREFETCH:-1000}"
BENCH_SEARCH_INDEX="${BENCH_SEARCH_INDEX:-1000}"
BENCH_USERS="${BENCH_USERS:-1 10 50}"
BENCH_SATURATION_USERS="${BENCH_SATURATION_USERS:-50}"
BENCH_SEARCH_THREADS="${BENCH_SEARCH_THREADS:-8 64}"
BENCH_SEARCH_REPLICAS="${BENCH_SEARCH_REPLICAS:-2}"
BENCH_LOAD_SECONDS="${BENCH_LOAD_SECONDS:-60}"
BENCH_LOAD_WARMUP="${BENCH_LOAD_WARMUP:-15}"
BROKER_URL="${BROKER_URL:-tcp://localhost:61616}"
PYTHON="${PYTHON:-python}"
MVN="${MVN:-mvn}"
MONGO_HOST_PORT="${MONGO_HOST_PORT:-localhost:27017}"
BROKER_HOST_PORT="${BROKER_HOST_PORT:-localhost:61616}"

COMMON_JAR="common/target/common-1.0.0-benchmarks.jar"
INDEXING_JAR="indexing-service/target/indexing-service-1.0.0-benchmarks.jar"
SEARCH_JAR="search-service/target/search-service-1.0.0-benchmarks.jar"
INTEGRATION_JAR="integration/target/integration-1.0.0.jar"

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
  integration  run the pipeline and search load experiments with the stack
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

build_integration() {
    [[ -e "$MVN" ]] || command -v "$MVN" >/dev/null || fail "Maven not found: set MVN to the mvn command"
    log "Building the services and the load tools"
    "$MVN" -q -B -Pbenchmark,integration -DskipTests package
}

stack_labels() {
    local experiment="$1" threads="$2" replicas="$3" users="$4" books="$5" index_size="$6" params="$7"
    printf 'run_id=%s,experiment=%s,threads=%s,replicas=%s,users=%s,xmx=%s,books=%s,index_size=%s,params=%s,smoke=%s' \
        "$RUN_ID" "$experiment" "$threads" "$replicas" "$users" "$SERVICE_XMX" "$books" "$index_size" "$params" "$SMOKE"
}

start_sampler() {
    STOP_FILE="$RUN_DIR/.stop-sampler"
    rm -f "$STOP_FILE"
    "$PYTHON" benchmark/sample_resources.py "$RUN_DIR/resources.csv" --stop-file "$STOP_FILE" --label "$1" &
    SAMPLER_PID=$!
}

stop_sampler() {
    touch "$STOP_FILE"
    wait "$SAMPLER_PID" || true
    rm -f "$STOP_FILE"
}

stop_stack() {
    KEEP_INFRA=true bash scripts/stop.sh
}

drop_pipeline_data() {
    rm -rf "$PIPELINE_DATALAKE"
    java -cp "$INDEXING_JAR" bdsm.indexing.benchmark.BenchmarkDatabases drop-one pipeline
}

pipeline_run() {
    local experiment="$1" workers="$2" replicas="$3" prefetch="$4" repeat label
    for ((repeat = 1; repeat <= BENCH_REPEATS; repeat++)); do
        label="$experiment-w$workers-r$replicas-p$prefetch-$repeat"
        log "Pipeline run $label"
        drop_pipeline_data
        BROKER_URL="$BROKER_URL" QUEUE_PREFETCH="$prefetch" INDEXING_WORKERS="$workers" INDEXING_REPLICAS="$replicas" \
            MONGO_DATABASE="$PIPELINE_DATABASE" DATALAKE_PATH="$PIPELINE_DATALAKE" BOOK_SOURCE=benchmark/cache/books \
            STACK_LOG_DIR="$LOG_DIR/$label" INFRA=external bash scripts/start.sh ingestion indexing
        start_sampler "$label"
        java -cp "$INTEGRATION_JAR" bdsm.integration.load.PipelineProbe \
            --books "$BENCH_PIPELINE_BOOKS" --books-csv "${BENCH_BOOKS_CSV:-benchmark/books.csv}" \
            --broker "$BROKER_URL" --indexing-url "http://localhost:${INDEXING_PORT:-7002}" --out "$RUN_DIR/pipeline.csv" \
            --labels "$(stack_labels "$experiment" "$workers" "$replicas" 0 "$BENCH_PIPELINE_BOOKS" "" "prefetch=$prefetch;repeat=$repeat")"
        stop_sampler
        stop_stack
    done
}

search_targets() {
    local replicas="$1" replica targets=""
    for ((replica = 0; replica < replicas; replica++)); do
        targets+="${targets:+,}http://localhost:$((${SEARCH_PORT:-7003} + replica * ${PORT_STEP:-10}))"
    done
    echo "$targets"
}

search_run() {
    local experiment="$1" threads="$2" replicas="$3" users="$4" repeat label
    for ((repeat = 1; repeat <= BENCH_REPEATS; repeat++)); do
        label="$experiment-t$threads-r$replicas-u$users-$repeat"
        log "Search run $label"
        SEARCH_HTTP_THREADS="$threads" SEARCH_REPLICAS="$replicas" MONGO_DATABASE="bench_index_$BENCH_SEARCH_INDEX" \
            STACK_LOG_DIR="$LOG_DIR/$label" INFRA=external bash scripts/start.sh search
        start_sampler "$label"
        java -cp "$INTEGRATION_JAR" bdsm.integration.load.LoadGenerator \
            --targets "$(search_targets "$replicas")" --users "$users" --queries benchmark/queries.txt \
            --duration-s "$BENCH_LOAD_SECONDS" --warmup-s "$BENCH_LOAD_WARMUP" --seed "$repeat" \
            --out "$RUN_DIR/search.csv" --check-file "$RUN_DIR/.search-check" \
            --labels "$(stack_labels "$experiment" "$threads" "$replicas" "$users" "$BENCH_SEARCH_INDEX" "$BENCH_SEARCH_INDEX" "repeat=$repeat")"
        stop_sampler
        stop_stack
    done
}

run_integration_experiments() {
    local value
    if [[ -z "$BENCH_ONLY" || "pipeline" =~ $BENCH_ONLY ]]; then
        pipeline_run pipeline-baseline 1 1 1
        for value in $BENCH_PIPELINE_WORKERS; do pipeline_run pipeline-workers "$value" 1 1; done
        for value in $BENCH_PIPELINE_REPLICAS; do pipeline_run pipeline-replicas 1 "$value" 1; done
        pipeline_run pipeline-prefetch 1 "$(echo "$BENCH_PIPELINE_REPLICAS" | cut -d' ' -f1)" "$BENCH_PIPELINE_PREFETCH"
    fi
    if [[ -z "$BENCH_ONLY" || "search" =~ $BENCH_ONLY ]]; then
        java "-Xmx$BENCH_XMX" -cp "$INDEXING_JAR" bdsm.indexing.benchmark.BenchmarkDatabases build "$BENCH_SEARCH_INDEX"
        for value in $BENCH_USERS; do search_run search-users 32 1 "$value"; done
        for value in $BENCH_SEARCH_THREADS; do search_run search-threads "$value" 1 "$BENCH_SATURATION_USERS"; done
        for value in $BENCH_SEARCH_REPLICAS; do search_run search-replicas 32 "$value" "$BENCH_SATURATION_USERS"; done
    fi
}

run_integration() {
    require_tools
    require_dataset
    build_integration
    port_open "$MONGO_HOST_PORT" || fail "No MongoDB on $MONGO_HOST_PORT"
    port_open "$BROKER_HOST_PORT" || fail "No broker on $BROKER_HOST_PORT"
    SMOKE=false
    SERVICE_XMX="${SERVICE_XMX:-2G}"
    export SERVICE_XMX
    PIPELINE_DATABASE="bench_pipeline"
    PIPELINE_DATALAKE="benchmark/tmp/pipeline/datalake"
    start_run integration
    trap 'stop_stack; drop_databases' EXIT
    run_integration_experiments
    log "Done: $RUN_DIR"
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
    integration) run_integration ;;
    drop-db) drop_databases ;;
    *) usage; exit 1 ;;
esac
