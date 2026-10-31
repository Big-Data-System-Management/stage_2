#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

KEEP_INFRA="${KEEP_INFRA:-false}"

process_gone() {
    ! process_alive "$1"
}

stop_service() {
    local service="$1" pid_file pid
    for pid_file in "$STACK_RUN_DIR/$service"-*.pid; do
        [[ -f "$pid_file" ]] || continue
        pid="$(cat "$pid_file")"
        if process_alive "$pid"; then
            kill_process "$pid"
            wait_for "Exit of pid $pid" process_gone "$pid"
            log "Stopped $(basename "$pid_file" .pid) (pid $pid)"
        fi
        rm -f "$pid_file"
    done
}

stop_infra() {
    local infra_file="$STACK_RUN_DIR/infra"
    if [[ "$KEEP_INFRA" != "true" && -f "$infra_file" && "$(cat "$infra_file")" == "docker" ]]; then
        log "Stopping MongoDB and ActiveMQ"
        docker compose down
        rm -f "$infra_file"
    fi
}

if (($# == 0)); then
    for service in "${SERVICES[@]}"; do
        stop_service "$service"
    done
    stop_infra
else
    for service in "$@"; do
        stop_service "$service"
    done
fi
