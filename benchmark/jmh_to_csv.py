import argparse
import csv
import json
import statistics
from pathlib import Path

COLUMNS = [
    "run_id", "scope", "experiment", "benchmark", "metric", "unit", "mean", "stddev", "samples",
    "threads", "replicas", "users", "xmx", "books", "index_size", "batch_size", "params", "smoke",
]
THREAD_PARAMS = ("threads", "consumers")
BOOK_PARAMS = ("indexSize",)


def thread_count(entry, params):
    for name in THREAD_PARAMS:
        if name in params:
            return params[name]
    return entry["threads"]


def to_rows(entries, args):
    rows = []
    for entry in entries:
        params = entry.get("params", {})
        values = [value for fork in entry["primaryMetric"]["rawData"] for value in fork]
        index_size = next((params[name] for name in BOOK_PARAMS if name in params), "")
        batch_size = args.batch_size if index_size else ""
        other = {name: value for name, value in params.items() if name not in THREAD_PARAMS + BOOK_PARAMS}
        rows.append({
            "run_id": args.run_id,
            "scope": "component",
            "experiment": args.experiment,
            "benchmark": entry["benchmark"].rsplit(".", 2)[-2] + "." + entry["benchmark"].rsplit(".", 1)[-1],
            "metric": entry["mode"],
            "unit": entry["primaryMetric"]["scoreUnit"],
            "mean": statistics.fmean(values),
            "stddev": statistics.stdev(values) if len(values) > 1 else 0.0,
            "samples": len(values),
            "threads": thread_count(entry, params),
            "replicas": 1,
            "users": 0,
            "xmx": args.xmx,
            "books": int(index_size) + args.batch_size if index_size else "",
            "index_size": index_size,
            "batch_size": batch_size,
            "params": ";".join(f"{name}={value}" for name, value in sorted(other.items())),
            "smoke": args.smoke,
        })
    return rows


def main():
    parser = argparse.ArgumentParser(description="Convert a JMH JSON result into a labelled CSV")
    parser.add_argument("source")
    parser.add_argument("target")
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--experiment", required=True)
    parser.add_argument("--xmx", required=True)
    parser.add_argument("--batch-size", type=int, default=20)
    parser.add_argument("--smoke", choices=["true", "false"], default="false")
    args = parser.parse_args()
    entries = json.loads(Path(args.source).read_text(encoding="utf-8"))
    with Path(args.target).open("w", encoding="utf-8", newline="") as file:
        writer = csv.DictWriter(file, fieldnames=COLUMNS, lineterminator="\n")
        writer.writeheader()
        writer.writerows(to_rows(entries, args))


if __name__ == "__main__":
    main()
