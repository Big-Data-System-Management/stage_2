# Stage 2 - Service-Oriented Architecture

Search engine over Project Gutenberg books, split into cooperating microservices (Java 17, Javalin, Gson, MongoDB, ActiveMQ). Every process runs on a single machine.

## Architecture

The services coordinate by choreography through an ActiveMQ broker: there is no central controller, and no service calls another one over HTTP.

```
          BookRequested            BookDownloaded             BookIndexed
[feeder] --------------> [ingestion] --------------> [indexing] ------------>
                              |                           |
                          datalake                     MongoDB  <----  [search]  <----  front-end (HTTP)
```

| Module | Port | Responsibility |
|---|---|---|
| `common` | - | Shared code: model, events and broker client, tokenizer, datalake (time hierarchy), HTTP helpers, configuration |
| `feeder` | - | Command-line tool that publishes `BookRequested` and `IndexRebuildRequested` |
| `ingestion-service` | 7001 | Consumes `BookRequested`, stores the book in the datalake, publishes `BookDownloaded` |
| `indexing-service` | 7002 | Consumes `BookDownloaded`, extracts metadata, updates the inverted index in MongoDB, publishes `BookIndexed` |
| `search-service` | 7003 | Front-end API: keyword search with filters and pagination |

## Contracts

| Specification | Interface | Consumer |
|---|---|---|
| [`api/search.yaml`](api/search.yaml) | HTTP (OpenAPI) | Front-end |
| [`api/events.yaml`](api/events.yaml) | Events (AsyncAPI) | Services, through the broker |
| [`api/ingestion.yaml`](api/ingestion.yaml) | HTTP (OpenAPI) | Operational status queries |
| [`api/indexing.yaml`](api/indexing.yaml) | HTTP (OpenAPI) | Operational status queries |

Runnable HTTP requests are in [`api/requests/`](api/requests). Routes answer `501 Not Implemented` until they are implemented.

## Requirements

- JDK 17 or newer
- Maven 3.9+
- MongoDB on `localhost:27017` and ActiveMQ Classic on `localhost:61616`, started with Docker or installed manually

### Option A: Docker

With [Docker Desktop](https://www.docker.com/products/docker-desktop/) running, start MongoDB 8.3.11 and ActiveMQ 6.2.0 from the project folder:

```bash
docker compose up -d
```

Stop them with `docker compose down`. Data is kept in Docker volumes between runs; `docker compose down -v` deletes it.

### Option B: manual installation

- MongoDB Community Server from [mongodb.com](https://www.mongodb.com/try/download/community)
- ActiveMQ Classic from [activemq.apache.org](https://activemq.apache.org/components/classic/download/): unzip it and run `bin\activemq start`

Do not run both options at the same time: they use the same ports.

The ActiveMQ web console is at `http://localhost:8161` (user `admin`, password `admin`).

## Build

```bash
mvn clean package
```

Each module produces an executable jar in `<module>/target/<module>-1.0.0.jar`.

## Run

Start MongoDB and ActiveMQ (for example with `docker compose up -d`), then each service in its own terminal:

```bash
java -jar ingestion-service/target/ingestion-service-1.0.0.jar
java -jar indexing-service/target/indexing-service-1.0.0.jar
java -jar search-service/target/search-service-1.0.0.jar
```

Request books 1 to 15, or a full index rebuild:

```bash
java -jar feeder/target/feeder-1.0.0.jar request 1 15
java -jar feeder/target/feeder-1.0.0.jar rebuild
```

Check that a service is up with `GET http://localhost:<port>/status`.

## Configuration

Settings are read from environment variables or `-D` system properties.

| Setting | Default | Used by |
|---|---|---|
| `INGESTION_PORT` / `INDEXING_PORT` / `SEARCH_PORT` | 7001 / 7002 / 7003 | each service |
| `INGESTION_HTTP_THREADS` / `INDEXING_HTTP_THREADS` / `SEARCH_HTTP_THREADS` | 8 / 8 / 32 | each service |
| `INGESTION_WORKERS` / `INDEXING_WORKERS` | 1 / 2 | event consumers per process |
| `BROKER_URL` | `tcp://localhost:61616` | every module except search |
| `MAX_REDELIVERIES` | 5 | every consumer |
| `REDELIVERY_DELAY_MS` | 1000 | every consumer |
| `DATALAKE_PATH` | `datalake` | ingestion, indexing |
| `BOOK_SOURCE` | `gutenberg` | ingestion; a folder such as `sample_dataset` reads books offline |
| `MONGO_URI` | `mongodb://localhost:27017` | indexing, search |
| `MONGO_DATABASE` | `search_engine` | indexing, search |

To run a second replica of a service, start another process with a different port, for example `java -DINDEXING_PORT=7012 -jar indexing-service/target/indexing-service-1.0.0.jar`. Replicas of a service share its event queue, so each event is processed by only one of them.

## Sample dataset

`sample_dataset/` holds 15 Gutenberg books (`pg1.txt` to `pg15.txt`) to test the pipeline without network access (`BOOK_SOURCE=sample_dataset`).
