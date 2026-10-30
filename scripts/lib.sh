ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

INGESTION_PORT="${INGESTION_PORT:-7001}"
INDEXING_PORT="${INDEXING_PORT:-7002}"
SEARCH_PORT="${SEARCH_PORT:-7003}"
INGESTION_REPLICAS="${INGESTION_REPLICAS:-1}"
INDEXING_REPLICAS="${INDEXING_REPLICAS:-1}"
SEARCH_REPLICAS="${SEARCH_REPLICAS:-1}"
PORT_STEP="${PORT_STEP:-10}"
SERVICE_XMX="${SERVICE_XMX:-2G}"
START_TIMEOUT_S="${START_TIMEOUT_S:-60}"
MONGO_HOST_PORT="${MONGO_HOST_PORT:-localhost:27017}"
BROKER_HOST_PORT="${BROKER_HOST_PORT:-localhost:61616}"
STACK_RUN_DIR="${STACK_RUN_DIR:-benchmark/run}"
STACK_LOG_DIR="${STACK_LOG_DIR:-benchmark/logs/stack}"
SERVICES=(ingestion indexing search)

log() {
    printf '[stack] %s\n' "$*"
}

fail() {
    printf '[stack] ERROR: %s\n' "$*" >&2
    exit 1
}

port_open() {
    local host="${1%:*}" port="${1##*:}"
    (exec 3<>"/dev/tcp/$host/$port") 2>/dev/null
}

wait_for() {
    local description="$1"
    shift
    local deadline=$((SECONDS + START_TIMEOUT_S))
    until "$@"; do
        ((SECONDS < deadline)) || fail "$description did not answer in $START_TIMEOUT_S s"
        sleep 0.5
    done
}

base_port() {
    case "$1" in
        ingestion) echo "$INGESTION_PORT" ;;
        indexing) echo "$INDEXING_PORT" ;;
        search) echo "$SEARCH_PORT" ;;
        *) fail "Unknown service: $1" ;;
    esac
}

replicas_of() {
    case "$1" in
        ingestion) echo "$INGESTION_REPLICAS" ;;
        indexing) echo "$INDEXING_REPLICAS" ;;
        search) echo "$SEARCH_REPLICAS" ;;
        *) fail "Unknown service: $1" ;;
    esac
}

port_of() {
    echo $(($(base_port "$1") + ($2 - 1) * PORT_STEP))
}

jar_of() {
    echo "$1-service/target/$1-service-1.0.0.jar"
}

status_ok() {
    curl -fs --max-time 2 -o /dev/null "http://localhost:$1/status"
}

listening_pid() {
    if [[ -n "${WINDIR:-}" ]]; then
        netstat -ano | tr -d '\r' | awk -v suffix=":$1" '
            $1 == "TCP" && substr($2, length($2) - length(suffix) + 1) == suffix && $3 ~ /:0$/ {print $NF; exit}'
    else
        ss -ltnpH "sport = :$1" | sed -n 's/.*pid=\([0-9]*\).*/\1/p' | head -1
    fi
}

record_pid() {
    local pid
    pid="$(listening_pid "$1")"
    [[ -n "$pid" ]] && echo "$pid" > "$2"
}

process_alive() {
    if [[ -n "${WINDIR:-}" ]]; then
        tasklist //FI "PID eq $1" //NH 2>/dev/null | grep -q "$1"
    else
        kill -0 "$1" 2>/dev/null
    fi
}

kill_process() {
    if [[ -n "${WINDIR:-}" ]]; then
        taskkill //F //PID "$1" >/dev/null 2>&1 || true
    else
        kill -9 "$1" 2>/dev/null || true
    fi
}
