#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

INFRA="${INFRA:-}"

start_infra() {
    if [[ -z "$INFRA" ]]; then
        INFRA=external
        if command -v docker >/dev/null && docker info >/dev/null 2>&1; then
            INFRA=docker
        fi
    fi
    if [[ "$INFRA" == "docker" ]]; then
        log "Starting MongoDB and ActiveMQ with Docker"
        docker compose up -d
    elif [[ "$INFRA" != "external" ]]; then
        fail "INFRA must be docker or external, got: $INFRA"
    fi
    echo "$INFRA" > "$STACK_RUN_DIR/infra"
    wait_for "MongoDB on $MONGO_HOST_PORT" port_open "$MONGO_HOST_PORT"
    wait_for "ActiveMQ on $BROKER_HOST_PORT" port_open "$BROKER_HOST_PORT"
    log "MongoDB and ActiveMQ are ready ($INFRA)"
}

start_replica() {
    local service="$1" replica="$2" port jar pid_file log_file port_setting
    port="$(port_of "$service" "$replica")"
    jar="$(jar_of "$service")"
    pid_file="$STACK_RUN_DIR/$service-$replica.pid"
    log_file="$STACK_LOG_DIR/$service-$replica.log"
    [[ -f "$jar" ]] || fail "$jar not found: run mvn clean package"
    if status_ok "$port"; then
        fail "Something already answers on port $port: run scripts/stop.sh first"
    fi
    port_setting="$(echo "$service" | tr '[:lower:]' '[:upper:]')_PORT"
    nohup java "-Xmx$SERVICE_XMX" "-D$port_setting=$port" -jar "$jar" >> "$log_file" 2>&1 &
    disown
    wait_for "$service replica $replica on port $port" status_ok "$port"
    wait_for "The process behind port $port" record_pid "$port" "$pid_file"
    log "$service replica $replica is ready on port $port (pid $(cat "$pid_file"))"
}

start_service() {
    local service="$1" replica
    for ((replica = 1; replica <= $(replicas_of "$service"); replica++)); do
        start_replica "$service" "$replica"
    done
}

mkdir -p "$STACK_RUN_DIR" "$STACK_LOG_DIR"
if (($# == 0)); then
    start_infra
    set -- "${SERVICES[@]}"
fi
for service in "$@"; do
    start_service "$service"
done
