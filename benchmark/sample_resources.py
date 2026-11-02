import argparse
import csv
import shutil
import subprocess
import time
from pathlib import Path

import psutil

BYTES_PER_MB = 1024 ** 2
SUPPORT_PROCESSES = {"mongod": "mongod", "mongod.exe": "mongod"}
CONTAINERS = {"stage2-mongodb": "mongod", "stage2-activemq": "activemq"}
UNITS = {"B": 1, "KIB": 1024, "MIB": 1024 ** 2, "GIB": 1024 ** 3, "KB": 1000, "MB": 1000 ** 2, "GB": 1000 ** 3}


def service_processes(pid_dir):
    processes = {}
    for pid_file in Path(pid_dir).glob("*.pid"):
        try:
            processes[pid_file.stem] = psutil.Process(int(pid_file.read_text().strip()))
        except (ValueError, psutil.NoSuchProcess):
            continue
    return processes


def support_processes():
    processes = {}
    for process in psutil.process_iter(["name", "cmdline"]):
        try:
            name = (process.info["name"] or "").lower()
            if name in SUPPORT_PROCESSES:
                processes[SUPPORT_PROCESSES[name]] = process
            elif name.startswith("java") and any("activemq" in part.lower() for part in process.info["cmdline"] or []):
                processes["activemq"] = process
        except (psutil.NoSuchProcess, psutil.AccessDenied):
            continue
    return processes


def docker_stats():
    if shutil.which("docker") is None:
        return {}
    result = subprocess.run(
        ["docker", "stats", "--no-stream", "--format", "{{.Name}},{{.CPUPerc}},{{.MemUsage}}"],
        capture_output=True, text=True)
    stats = {}
    for line in result.stdout.splitlines():
        name, cpu, memory = line.split(",", 2)
        if name in CONTAINERS:
            used = memory.split("/")[0].strip()
            unit = used.lstrip("0123456789.").upper()
            stats[CONTAINERS[name]] = (float(cpu.rstrip("%")), float(used[: len(used) - len(unit)]) * UNITS[unit] / BYTES_PER_MB)
    return stats


def main():
    parser = argparse.ArgumentParser(description="Sample CPU and memory per process and for the whole host")
    parser.add_argument("out")
    parser.add_argument("--pid-dir", default="benchmark/run")
    parser.add_argument("--stop-file", required=True)
    parser.add_argument("--label", default="")
    parser.add_argument("--interval", type=float, default=1.0)
    args = parser.parse_args()
    target = Path(args.out)
    is_new = not target.exists()
    logical_cores = psutil.cpu_count(logical=True)
    psutil.cpu_percent()
    known = {}
    start = time.monotonic()
    with target.open("a", encoding="utf-8", newline="") as file:
        writer = csv.writer(file, lineterminator="\n")
        if is_new:
            writer.writerow(["label", "elapsed_s", "process", "cpu_percent", "memory_mb"])
        while not Path(args.stop_file).exists():
            time.sleep(args.interval)
            elapsed = round(time.monotonic() - start, 1)
            memory = psutil.virtual_memory()
            writer.writerow([args.label, elapsed, "host", psutil.cpu_percent(), round(memory.used / BYTES_PER_MB, 1)])
            current = {**service_processes(args.pid_dir), **support_processes()}
            for name, process in current.items():
                key = (name, process.pid)
                if key not in known:
                    known[key] = process
                    process.cpu_percent()
                    continue
                try:
                    cpu = known[key].cpu_percent() / logical_cores
                    writer.writerow([args.label, elapsed, name, round(cpu, 1), round(known[key].memory_info().rss / BYTES_PER_MB, 1)])
                except psutil.NoSuchProcess:
                    continue
            for name, (cpu, used) in docker_stats().items():
                writer.writerow([args.label, elapsed, name, round(cpu / logical_cores, 1), round(used, 1)])
            file.flush()


if __name__ == "__main__":
    main()
