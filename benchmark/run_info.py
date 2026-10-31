import json
import os
import platform
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path

import psutil


def run(command):
    try:
        return subprocess.run(command, capture_output=True, text=True, timeout=30).stdout.strip()
    except (OSError, subprocess.SubprocessError):
        return ""


def cpu_name():
    if platform.system() == "Windows":
        return run(["powershell", "-NoProfile", "-Command", "(Get-CimInstance Win32_Processor).Name"])
    if Path("/proc/cpuinfo").exists():
        for line in Path("/proc/cpuinfo").read_text().splitlines():
            if line.startswith("model name"):
                return line.split(":", 1)[1].strip()
    return platform.processor()


def storage():
    if platform.system() == "Windows":
        return run(["powershell", "-NoProfile", "-Command",
                    "(Get-PhysicalDisk | ForEach-Object { $_.FriendlyName + ' ' + $_.MediaType }) -join '; '"])
    return run(["lsblk", "-d", "-o", "NAME,ROTA,SIZE,MODEL", "-n"])


def java_version():
    result = subprocess.run(["java", "-version"], capture_output=True, text=True)
    return result.stderr.splitlines()[0] if result.stderr else ""


def main():
    out, run_id, kind = sys.argv[1], sys.argv[2], sys.argv[3]
    info = {
        "run_id": run_id,
        "kind": kind,
        "started_at": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "host": {
            "cpu": cpu_name(),
            "physical_cores": psutil.cpu_count(logical=False),
            "logical_cores": psutil.cpu_count(logical=True),
            "ram_gb": round(psutil.virtual_memory().total / 1024 ** 3, 1),
            "storage": storage(),
            "os": platform.platform(),
        },
        "runtime": {
            "java": java_version(),
            "python": platform.python_version(),
            "mongodb": os.environ.get("BENCH_MONGO_VERSION", ""),
        },
        "git": {
            "commit": run(["git", "rev-parse", "--short", "HEAD"]),
            "branch": run(["git", "rev-parse", "--abbrev-ref", "HEAD"]),
            "dirty": bool(run(["git", "status", "--porcelain", "--", ":!benchmark/books.csv"])),
        },
        "settings": {
            name: value for name, value in sorted(os.environ.items())
            if name.startswith("BENCH_") or name in ("MONGO_URI", "BROKER_URL")
        },
    }
    Path(out).write_text(json.dumps(info, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
