# Stage 2 — Service-Oriented Architecture

Search Engine Project · Big Data · Grado en Ciencia e Ingeniería de Datos · Universidad de Las Palmas de Gran Canaria

This repository turns the search engine over [Project Gutenberg](https://www.gutenberg.org/) books into cooperating microservices (Java 17, Javalin, Gson, MongoDB, ActiveMQ):

- an **ingestion service** that downloads books and stores them in the datalake,
- an **indexing service** that extracts their metadata and keeps an inverted index in MongoDB,
- a **search service** with the HTTP API for the front-end: keyword search with filters and pagination,
- **events** through an ActiveMQ broker that coordinate the services without a central controller,
- **validation tests** of the APIs and events, and **benchmarks** of each component and of the whole system.

**Group:** Big Data System & Management

**Members:**

- Adrián Rodríguez Pérez
- Pablo Castillo Garcerá
- Pablo Martín Perdomo
- Joel López Guillén

---

## Table of contents

1. [Repository layout](#repository-layout)
2. [Architecture](#architecture)
3. [Endpoints](#endpoints)
4. [Requirements](#requirements)
5. [Build](#build)
6. [Run](#run)
7. [Configuration](#configuration)
8. [Validation](#validation)
9. [Benchmarks](#benchmarks)
10. [Sample dataset](#sample-dataset)
11. [Code documentation](#code-documentation)
12. [Known limitations](#known-limitations)

---

## Repository layout

```
stage_2/
├── pom.xml                  Parent Maven project (Java 17) with the benchmark and integration profiles
├── docker-compose.yml       MongoDB 8.3.11 and ActiveMQ 6.2.0
├── common/                  Shared code: config, datalake, events and broker client, HTTP, JSON, model, tokenizer
├── feeder/                  Command-line tool that publishes BookRequested and IndexRebuildRequested
├── ingestion-service/       api, events, source (Gutenberg download and split), storage (datalake locator)
├── indexing-service/        api, events, index (MongoDB datamart), metadata (header parser)
├── search-service/          api, query, repository (MongoDB reads)
├── integration/             Validation tests (*IT) and load tools (load generator, pipeline probe)
├── api/                     Contracts: OpenAPI per service, AsyncAPI for the events
│   └── requests/            Runnable HTTP requests for every endpoint
├── scripts/                 start.sh, stop.sh, validate.sh
├── benchmark/
│   ├── run.sh               Runs the benchmarks: dataset, micro, integration, smoke, drop-db
│   ├── books.csv            The 2020 benchmark books and their role
│   ├── queries.txt          Queries of the search load
│   ├── *.py                 Book download, JMH to CSV, run information, resource sampling, plots
│   ├── cache/               (generated) downloaded books
│   ├── results/             (generated) CSV results per run
│   ├── plots/               (generated) PNG plots per run
│   └── logs/                (generated) logs of the services, validation and benchmarks
└── sample_dataset/          27 Gutenberg books to run everything offline
```

Each service has an `App` class that wires it, an `api` package with its HTTP routes and, except search, an `events` package with its event handlers. The JMH benchmarks are in `src/test/java/.../benchmark` of each module. Folders marked as *generated* are git-ignored, as are `datalake/`, `benchmark/run/` and `benchmark/tmp/`.

---

## Architecture

The services coordinate by choreography through an ActiveMQ broker: there is no central controller, and no service calls another one over HTTP. Each service reacts to the events it consumes and publishes the next one.

```
          BookRequested             BookDownloaded              BookIndexed
[feeder] ---------------> [ingestion] ---------------> [indexing] ---------->
   |                           |                        ^  ^   |
   |                           |                        |  |   |
   |                           v       reads books      |  |   v
   |                       datalake --------------------+  | MongoDB
   |                                                       |   ^
   +------------- IndexRebuildRequested -------------------+   |
                                                               |
                                                           [search]
                                                               ^
                                                       front-end (HTTP)
```

| Module | Port | Responsibility |
|---|---|---|
| `common` | - | Shared code: model, events and broker client, tokenizer, datalake (time hierarchy), HTTP helpers, configuration |
| `feeder` | - | Command-line tool that publishes `BookRequested` and `IndexRebuildRequested` |
| `ingestion-service` | 7001 | Consumes `BookRequested`, downloads the book, stores header and body in the datalake, publishes `BookDownloaded` |
| `indexing-service` | 7002 | Consumes `BookDownloaded`, extracts metadata, updates the inverted index in MongoDB, publishes `BookIndexed`; consumes `IndexRebuildRequested` |
| `search-service` | 7003 | Front-end API: keyword search with filters and pagination, read-only over MongoDB |
| `integration` | - | Validation tests and load tools, built only with the `integration` profile |

How the events flow:

- Every event goes to an ActiveMQ virtual topic `VirtualTopic.<Event>`. Each consuming service reads its own queue `Consumer.<group>.VirtualTopic.<Event>`, so every service gets one copy and the replicas of a service share it.
- Consumers use transacted sessions: a message is acknowledged only when its handler finishes, together with the events the handler published. A failure rolls both back and the message is redelivered up to 5 times (after 1, 2, 4, 8 and 16 s), then it goes to `ActiveMQ.DLQ`.
- Delivery is at least once, so every handler is idempotent: a stored book is not downloaded again, a published book is not indexed again, and postings are updated with `$addToSet`.
- Indexing writes a book with status `indexing` and switches it to `published` when its postings are written. Search only returns published books.
- On a new broker, start the services before the feeder: the broker only copies events into queues that already exist.

The full rules per event are in [`api/events.yaml`](api/events.yaml).

---

## Endpoints

There are no internal HTTP endpoints: the services talk only through the broker. No HTTP request triggers work either; the feeder publishes the events instead, so there is no `POST /ingest/{book_id}` or `POST /index/rebuild`.

### Front-end

Consumed by the front-end. Contract: [`api/search.yaml`](api/search.yaml).

| Method and path | Service | Purpose |
|---|---|---|
| `GET /search?q=&author=&language=&year=&offset=&limit=` | search (7003) | Books that contain every term of `q`, with optional filters, ordered by `book_id` |
| `GET /search/filters` | search (7003) | Languages and years available for the filters |
| `GET /books/{book_id}` | search (7003) | Metadata of one published book |

`author` is a case-insensitive partial match, `language` an ISO 639-1 code and `year` an exact year. `limit` goes from 1 to 100 (default 20). Errors return `400 bad_request`, `404 not_found` or `503 service_unavailable` when MongoDB is down.

### Internal

Consumed by the services, through the broker. Contract: [`api/events.yaml`](api/events.yaml).

| Event | Producer | Consumer | Payload |
|---|---|---|---|
| `BookRequested` | feeder | ingestion | `{"book_id": 11}` |
| `BookDownloaded` | ingestion | indexing | `{"book_id": 11, "path": "20261028/10"}`, folder relative to `DATALAKE_PATH` |
| `BookIndexed` | indexing | none yet | `{"book_id": 11}` |
| `IndexRebuildRequested` | feeder | indexing | `{}` |

### Operational

Consumed by operators and scripts. Contracts: [`api/ingestion.yaml`](api/ingestion.yaml), [`api/indexing.yaml`](api/indexing.yaml).

| Method and path | Service | Purpose |
|---|---|---|
| `GET /status` | every service | Health check: `{"service": "...", "status": "running"}` |
| `GET /ingest/status/{book_id}` | ingestion (7001) | `available` or `missing` in the datalake |
| `GET /ingest/list` | ingestion (7001) | Identifiers of the books in the datalake |
| `GET /index/status` | indexing (7002) | Published books, last update and index size |

Runnable requests for every endpoint are in [`api/requests/`](api/requests).

---

## Requirements

| Component | Needed for | Version used |
|---|---|---|
| JDK | Building and running everything | 17 or newer (built and measured with 25) |
| Maven | Build | 3.9 (the one bundled with IntelliJ IDEA works) |
| MongoDB | Index and metadata, on `localhost:27017` | 8.3.11 |
| ActiveMQ Classic | Events, on `localhost:61616` | 6.2.0 |
| Docker Desktop | Optional way to start MongoDB and ActiveMQ | Docker Engine 29.9 |
| Bash | Scripts (Git Bash on Windows) | - |
| Python | Benchmarks and plots only | 3.12, with `python -m pip install -r benchmark/requirements.txt` |
| Internet | Downloading books, unless `BOOK_SOURCE` points to a folder | - |

Everything was tested and measured on Windows 11. The scripts also have the Linux commands, but Linux has not been tested.

### MongoDB and ActiveMQ with Docker

With [Docker Desktop](https://www.docker.com/products/docker-desktop/) running, from the project folder:

```bash
docker compose up -d
```

This starts MongoDB 8.3.11 and ActiveMQ 6.2.0. Stop them with `docker compose down`. Data is kept in Docker volumes between runs; `docker compose down -v` deletes it. The ActiveMQ web console is at `http://localhost:8161` (user `admin`, password `admin`).

### MongoDB and ActiveMQ installed manually

- MongoDB Community Server from [mongodb.com](https://www.mongodb.com/try/download/community)
- ActiveMQ Classic from [activemq.apache.org](https://activemq.apache.org/components/classic/download/): unzip it and run `java -jar bin/activemq.jar start` from its folder

Do not run Docker and a manual installation at the same time: they use the same ports.

---

## Build

```bash
mvn clean package
```

Each module produces an executable jar in `<module>/target/<module>-1.0.0.jar`. The unit tests run during the build; the MongoDB tests skip themselves when no MongoDB is running.

---

## Run

### With the scripts

```bash
bash scripts/start.sh
bash scripts/stop.sh
```

`start.sh` starts MongoDB and ActiveMQ with Docker when Docker is available, or uses the ones already running. Then it starts every service and waits for its `/status`. Logs go to `benchmark/logs/stack/`. One service can be started or stopped alone, for example `bash scripts/start.sh indexing`.

### By hand

Start MongoDB and ActiveMQ, then each service in its own terminal:

```bash
java -jar ingestion-service/target/ingestion-service-1.0.0.jar
java -jar indexing-service/target/indexing-service-1.0.0.jar
java -jar search-service/target/search-service-1.0.0.jar
```

### Request books

Request books 1 to 15, or a full index rebuild from the datalake:

```bash
java -jar feeder/target/feeder-1.0.0.jar request 1 15
java -jar feeder/target/feeder-1.0.0.jar rebuild
```

Then search:

```bash
curl "http://localhost:7003/search?q=alice&language=en"
```

---

## Configuration

Settings are read from environment variables or `-D` system properties.

### Services

| Setting | Default | Used by |
|---|---|---|
| `INGESTION_PORT` / `INDEXING_PORT` / `SEARCH_PORT` | 7001 / 7002 / 7003 | each service |
| `INGESTION_HTTP_THREADS` / `INDEXING_HTTP_THREADS` / `SEARCH_HTTP_THREADS` | 8 / 8 / 32 | HTTP threads of each service |
| `INGESTION_WORKERS` / `INDEXING_WORKERS` | 1 / 2 | event consumers per process |
| `BROKER_URL` | `tcp://localhost:61616` | every module except search |
| `QUEUE_PREFETCH` | 1 | messages each consumer reserves in advance |
| `MAX_REDELIVERIES` | 5 | every consumer |
| `REDELIVERY_DELAY_MS` | 1000 | first redelivery delay, doubled on every retry |
| `DATALAKE_PATH` | `datalake` | ingestion, indexing |
| `BOOK_SOURCE` | `gutenberg` | ingestion; a folder such as `sample_dataset` reads books offline |
| `MONGO_URI` | `mongodb://localhost:27017` | indexing, search |
| `MONGO_DATABASE` | `search_engine` | indexing, search |
| `SEARCH_CORS_ORIGIN` | `*` | search |

With `BOOK_SOURCE=gutenberg`, ingestion downloads from the `gutenberg.pglaf.org` mirror, about one request per second, because Project Gutenberg does not allow automated access to its main site.

### Threads and replicas

There are two ways to process more events in parallel:

- More workers in one process: `INDEXING_WORKERS=4` runs 4 consumers of the indexing queue.
- More replicas: extra processes of the same service. Replicas share the queue, so each event is processed by only one of them. With `QUEUE_PREFETCH=1` they share the work evenly.

With the scripts, replicas get ports in steps of 10 (7002, 7012, 7022 for indexing):

```bash
INDEXING_REPLICAS=2 SEARCH_REPLICAS=2 bash scripts/start.sh
```

By hand, start another process with a different port, for example `java -DINDEXING_PORT=7012 -jar indexing-service/target/indexing-service-1.0.0.jar`. Search has no load balancer: each replica answers on its own port.

### Scripts

| Setting | Default | Purpose |
|---|---|---|
| `INGESTION_REPLICAS` / `INDEXING_REPLICAS` / `SEARCH_REPLICAS` | 1 | replicas of each service |
| `PORT_STEP` | 10 | port step between replicas |
| `SERVICE_XMX` | 2G | `-Xmx` of each service |
| `START_TIMEOUT_S` | 60 | maximum wait for each service |
| `INFRA` | automatic | `docker`, or `external` to use a MongoDB and ActiveMQ already running |
| `KEEP_INFRA` | false | `true` so that `stop.sh` does not stop Docker |
| `MONGO_HOST_PORT` / `BROKER_HOST_PORT` | `localhost:27017` / `localhost:61616` | where to check that they are up |
| `STACK_RUN_DIR` / `STACK_LOG_DIR` | `benchmark/run` / `benchmark/logs/stack` | PID files and logs |
| `MVN` | `mvn` | Maven command, for example the one bundled with IntelliJ |

---

## Validation

```bash
bash scripts/validate.sh
```

It builds the services, starts everything with its own database (`validation`) and datalake, runs the 48 validation tests against the running services and stops everything. It reads books from `sample_dataset`, so it needs no network access. It takes about 5 minutes. To run one class only: `bash scripts/validate.sh SearchApiIT`. Reports go to `benchmark/logs/validation/`.

Expected result: 48 tests, 0 failures.

| Class | Tests | Checks | Expected result |
|---|---|---|---|
| `SearchApiIT` | 32 | Keywords, each filter, combined filters, author as literal text, empty results, 12 invalid parameters, pagination, `/books/{book_id}`, `/search/filters`, unpublished books stay hidden | 200 with the exact books, 400 `bad_request`, 404 `not_found` |
| `OperationalApiIT` | 7 | `/status` of the three services, `/index/status`, `/ingest/status/{book_id}`, `/ingest/list` | The values of the contracts |
| `PipelineIT` | 1 | `feeder request 13` ends up in `/search` | The book appears in less than 60 s |
| `IdempotencyIT` | 3 | The same book requested twice, `BookDownloaded` repeated, two copies at the same time | One record in `books`, one document per term, the id once in its postings, `books_indexed` equal to 1 |
| `QueueDurabilityIT` | 2 | With indexing or ingestion stopped, the event waits in its queue | Processed when the service starts again |
| `DeadLetterIT` | 2 | An event that always fails and a message that is not JSON | Both end in `ActiveMQ.DLQ` after 5 redeliveries |
| `ReplicasIT` | 1 | Two indexing replicas with 12 books | Each `BookIndexed` arrives once and both replicas work |

These tests do not run in `mvn clean package`: the `integration` module only exists with the `-Pintegration` profile.

---

## Benchmarks

The book list is fixed in `benchmark/books.csv` (2020 books). Download them once into `benchmark/cache/`; it takes about 3 hours and can be stopped and resumed:

```bash
bash benchmark/run.sh dataset
```

Then, with MongoDB and ActiveMQ running:

| Command | What it does | Time on the measurement PC |
|---|---|---|
| `bash benchmark/run.sh micro` | Every JMH benchmark with 5 warm-up and 10 measurement iterations | 1 h 30 min |
| `bash benchmark/run.sh integration` | Pipeline and search load experiments with the services running | 1 h |
| `bash benchmark/run.sh smoke` | Every JMH benchmark once, only to check that it works; its numbers are not valid | 3 min |
| `bash benchmark/run.sh drop-db` | Drops the `bench_*` databases left by an interrupted run | - |
| `python benchmark/plot.py --run <folder>` | Draws the plots of one results folder | - |

Each run writes to `benchmark/results/<date>-<kind>/`: one CSV per experiment, the raw JMH JSON in `raw/` and `run.json` with the hardware, versions, commit and settings. Plots go to `benchmark/plots/<folder>/`. Rows with `valid=false` mean that the run gave wrong results. `BENCH_ONLY` runs only the experiments that match a regular expression, for example `BENCH_ONLY='^index-growth$' bash benchmark/run.sh micro`. Keep the PC free while measuring.

| Setting | Default | Purpose |
|---|---|---|
| `BENCH_XMX` | 4G | heap of the JMH processes |
| `BENCH_WARMUP` / `BENCH_ITERATIONS` / `BENCH_FORKS` | 5 / 10 / 1 | JMH settings |
| `BENCH_INDEX_SIZES` | 100,500,1000,2000 | index sizes, in books |
| `BENCH_THREADS` / `BENCH_THREADS_INDEX` | 1,2,4,8 / 1000 | indexing threads, and the index size used to compare them |
| `BENCH_REBUILD` | false | `true` rebuilds the benchmark jars |
| `BENCH_KEEP_DB` | false | `true` keeps the `bench_*` databases |
| `BENCH_REPEATS` | 3 | repetitions of each integration experiment |
| `BENCH_PIPELINE_BOOKS` | 200 | books per pipeline run |
| `BENCH_PIPELINE_WORKERS` / `BENCH_PIPELINE_REPLICAS` / `BENCH_PIPELINE_PREFETCH` | 2 4 8 / 2 4 / 1000 | values compared with the baseline of 1 worker, 1 replica and prefetch 1 |
| `BENCH_SEARCH_INDEX` | 1000 | index size for the search load |
| `BENCH_USERS` / `BENCH_SATURATION_USERS` | 1 10 50 / 50 | concurrent users |
| `BENCH_SEARCH_THREADS` / `BENCH_SEARCH_REPLICAS` | 8 64 / 2 | values compared with the baseline of 32 threads and 1 replica |
| `BENCH_LOAD_SECONDS` / `BENCH_LOAD_WARMUP` | 60 / 15 | duration of each load |
| `PYTHON` / `MVN` | `python` / `mvn` | commands to use |

---

## Sample dataset

`sample_dataset/` holds 27 Gutenberg books to run the pipeline without network access (`BOOK_SOURCE=sample_dataset`):

- `pg1.txt` to `pg15.txt`, and 12 recent books with a publication year: 70000, 70500, 71000, 71250, 72500, 73500, 73666, 73750, 74750, 75000, 75500 and 75888
- 23 in English, 2 in Spanish, 1 in Finnish and 1 in German

---

## Code documentation

The source code has no comments by decision of the team. The behaviour is documented in the contracts of [`api/`](api), in this README and in the names of the tests, which describe each rule they check.

---

## Known limitations

- **Docker Desktop on Windows.** It adds about 44 ms to every MongoDB message larger than about 2 KB and slows down the confirmations to ActiveMQ, because of the port forwarding between Windows and its virtual machine. It shows in large searches and in the broker throughput. The report compares those results with a MongoDB and ActiveMQ installed natively.
- **Start-up order.** On a new broker, events published before a service has subscribed once are dropped, because their queue does not exist yet. Start the services before the feeder.
- **No ranking.** Results are ordered by `book_id`, not by relevance.
- **No load balancer.** Each search replica answers on its own port, so the client chooses the replica.
- **Index rebuild.** The rebuild replaces the index and the metadata collections one after the other, so a search in that instant can combine the new index with the previous metadata.
