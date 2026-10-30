import argparse
import json
from pathlib import Path

import matplotlib

matplotlib.use("Agg")

import matplotlib.pyplot as plt
import pandas as pd

BENCHMARK_DIR = Path(__file__).resolve().parent
RESULTS_DIR = BENCHMARK_DIR / "results"
PLOTS_DIR = BENCHMARK_DIR / "plots"

SURFACE = "#fcfcfb"
PRIMARY_INK = "#0b0b0b"
SECONDARY_INK = "#52514e"
MUTED_INK = "#898781"
GRID = "#e1e0d9"
BASELINE = "#c3c2b7"
SERIES = ["#2a78d6", "#eb6834", "#1baf7a", "#eda100"]

plt.rcParams.update({
    "figure.facecolor": SURFACE,
    "axes.facecolor": SURFACE,
    "axes.edgecolor": BASELINE,
    "axes.labelcolor": SECONDARY_INK,
    "axes.titlecolor": PRIMARY_INK,
    "axes.titlesize": 12,
    "axes.titleweight": "bold",
    "axes.titlelocation": "left",
    "axes.grid": True,
    "axes.axisbelow": True,
    "axes.spines.top": False,
    "axes.spines.right": False,
    "grid.color": GRID,
    "grid.linewidth": 0.8,
    "xtick.color": MUTED_INK,
    "ytick.color": MUTED_INK,
    "xtick.labelcolor": SECONDARY_INK,
    "ytick.labelcolor": SECONDARY_INK,
    "text.color": PRIMARY_INK,
    "legend.frameon": False,
    "legend.labelcolor": SECONDARY_INK,
    "font.family": ["Segoe UI", "DejaVu Sans"],
    "font.size": 10,
    "savefig.dpi": 200,
    "savefig.bbox": "tight",
})


def line(axis, x, y, error, color, label):
    axis.errorbar(x, y, yerr=error, color=color, label=label, linewidth=2, marker="o", markersize=7,
                  markeredgecolor=SURFACE, markeredgewidth=1.5, capsize=3, elinewidth=1)


def footnote(figure, info, text):
    host = info.get("host", {})
    machine = f"{host.get('cpu', 'unknown CPU')}, {host.get('ram_gb', '?')} GB RAM"
    figure.text(0, -0.04, f"{text} {machine}. Run {info.get('run_id', '')}.", color=MUTED_INK, fontsize=8)


def save(figure, target, name):
    target.mkdir(parents=True, exist_ok=True)
    path = target / f"{name}.png"
    figure.savefig(path)
    plt.close(figure)
    print(f"[plot] {path}")


def plot_index_growth(results, info, target):
    data = results[results.experiment == "index-growth"].sort_values("index_size")
    if data.empty:
        return
    figure, axis = plt.subplots(figsize=(7, 4))
    line(axis, data.index_size, data["mean"], data["stddev"], SERIES[0], None)
    axis.set_title("Cost of adding the same 20 books as the index grows")
    axis.set_xlabel("Books already in the index")
    axis.set_ylabel("Seconds per added book")
    axis.set_ylim(bottom=0)
    footnote(figure, info, "MongoDatamart.index, 1 thread, reserved batch outside every base index.")
    save(figure, target, "index_growth")


def plot_index_threads(results, info, target):
    data = results[results.experiment.isin(["index-threads", "index-handler"])]
    if data.empty:
        return
    labels = {"index-threads": "MongoDatamart.index", "index-handler": "Full BookDownloaded handler"}
    figure, axis = plt.subplots(figsize=(7, 4))
    for color, (experiment, group) in zip(SERIES, data.groupby("experiment", sort=False)):
        group = group.sort_values("threads")
        throughput = 1 / group["mean"]
        line(axis, group.threads, throughput, group["stddev"] / group["mean"] ** 2, color, labels[experiment])
    axis.set_title("Indexing throughput with more threads")
    axis.set_xlabel("Worker threads")
    axis.set_ylabel("Books per second")
    axis.set_xticks(sorted(data.threads.unique()))
    axis.set_ylim(bottom=0)
    axis.legend()
    footnote(figure, info, f"Index of {data.index_size.iloc[0]} books, same 20-book batch in every configuration.")
    save(figure, target, "index_threads")


def plot_addtoset(results, info, target):
    data = results[results.experiment == "addtoset"].copy()
    if data.empty:
        return
    parts = data.params.str.extract(r"bookId=(?P<book_id>\w+);postings=(?P<postings>\d+)")
    data["kind"], data["postings"] = parts.book_id, parts.postings.astype(int)
    labels = {"new": "New book id (list grows)", "repeated": "Repeated book id (no change)"}
    figure, axis = plt.subplots(figsize=(7, 4))
    for color, (kind, group) in zip(SERIES, data.groupby("kind")):
        group = group.sort_values("postings")
        line(axis, group.postings, group["mean"], group["stddev"], color, labels[kind])
    axis.set_xscale("log")
    axis.set_yscale("log")
    axis.set_title("$addToSet cost against the length of the postings list")
    axis.set_xlabel("Books in the postings list of the term (log scale)")
    axis.set_ylabel("Microseconds per update (log scale)")
    axis.legend()
    footnote(figure, info, "One synthetic term, one update at a time.")
    save(figure, target, "addtoset")


def plot_eventbus(results, info, target):
    data = results[results.experiment.str.startswith("eventbus")].copy()
    if data.empty:
        return
    data["handler"] = data.params.str.extract(r"handlerMs=(\d+)")[0].astype(int)
    titles = {"eventbus-embedded": "Embedded broker, not persistent", "eventbus-external": "Real broker, persistent"}
    panels = [name for name in titles if name in set(data.experiment)]
    figure, axes = plt.subplots(1, len(panels), figsize=(5 * len(panels), 4), squeeze=False, sharey=True)
    for axis, panel in zip(axes[0], panels):
        for color, (handler, group) in zip(SERIES, data[data.experiment == panel].groupby("handler")):
            group = group.sort_values("threads")
            line(axis, group.threads, group["mean"], group["stddev"], color, f"Handler takes {handler} ms")
        axis.set_title(titles[panel])
        axis.set_xlabel("Consumers")
        axis.set_xticks(sorted(data.threads.unique()))
        axis.set_yscale("log")
        axis.legend()
    axes[0][0].set_ylabel("Events per second (log scale)")
    footnote(figure, info, "EventBus publish and consume.")
    save(figure, target, "eventbus")


def plot_text_processing(results, info, target):
    panels = [("tokenizer", "Tokenizer", "bookId"), ("metadata", "MetadataExtractor", "bookId")]
    data = [(results[results.experiment == name], title, key) for name, title, key in panels]
    data = [item for item in data if not item[0].empty]
    if not data:
        return
    figure, axes = plt.subplots(1, len(data), figsize=(5 * len(data), 3.6), squeeze=False)
    for axis, (group, title, key) in zip(axes[0], data):
        labels = "book " + group.params.str.extract(rf"{key}=(\d+)")[0]
        axis.bar(labels, group["mean"], yerr=group["stddev"], color=SERIES[0], width=0.5, capsize=3)
        axis.set_title(title)
        axis.set_ylabel(f"Average time ({group.unit.iloc[0].removesuffix('/op').replace('us', 'µs')} per book)")
        axis.grid(axis="x", visible=False)
    footnote(figure, info, "One thread, books from sample_dataset.")
    save(figure, target, "text_processing")


def available_runs(include_smoke):
    runs = []
    for run in sorted(path for path in RESULTS_DIR.iterdir() if path.is_dir()):
        if include_smoke or not run.name.endswith("-smoke"):
            runs.append(run)
    return runs


def load(run):
    frames = [pd.read_csv(path) for path in sorted(run.glob("*.csv"))]
    if not frames:
        raise SystemExit(f"[plot] No CSV files in {run}")
    results = pd.concat(frames, ignore_index=True)
    info_path = run / "run.json"
    info = json.loads(info_path.read_text(encoding="utf-8")) if info_path.exists() else {}
    return results, info


def main():
    parser = argparse.ArgumentParser(description="Draw the benchmark charts of one run")
    parser.add_argument("--run", help="run folder inside benchmark/results; the latest one by default")
    parser.add_argument("--include-smoke", action="store_true", help="also accept smoke runs")
    args = parser.parse_args()
    runs = [RESULTS_DIR / args.run] if args.run else available_runs(args.include_smoke)[-1:]
    if not runs or not runs[0].is_dir():
        raise SystemExit("[plot] No benchmark results found")
    results, info = load(runs[0])
    target = PLOTS_DIR / runs[0].name
    for plot in (plot_index_growth, plot_index_threads, plot_addtoset, plot_eventbus, plot_text_processing):
        plot(results, info, target)


if __name__ == "__main__":
    main()
