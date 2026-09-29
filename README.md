# Organisation & User Catalogue Service

A Spring Boot microservice that manages and serves the **organisation** and **user** catalogues for the Open Agri Stack — the organisations (and their departments) that consume the platform's catalogues, and the users onboarded against those organisations — backed by PostgreSQL for storage, Elasticsearch for fast search, and Redis for caching.

## Overview

This service is part of the **OpenAgriStack** ecosystem and is built on the **VERG** registry framework (see [TECH-README.md](TECH-README.md) and [Catalogue-Spec.MD](Catalogue-Spec.MD)). It exposes REST APIs to onboard and manage:

- **Organisation** records — e.g. government departments, research institutes, and NGOs such as the departments of Horticulture, Soil Science, or Plant Pathology.
- **User** records — the users onboarded against those organisations.

Every catalogue runs the same **maker-checker lifecycle** (draft → pending → approved → active, plus rework / reject / toggle / soft-delete), so records are reviewed before they go live. Each record is stored schemalessly: an incoming JSON document is validated against a per-catalogue JSON Schema, persisted in PostgreSQL (as a `jsonb` column), indexed into Elasticsearch for search, and cached in Redis.

## Features

- Maker-checker **record lifecycle** — draft, submit, approve, review/publish, toggle active/inactive, and soft-delete (see [Record Lifecycle](#record-lifecycle))
- Full set of per-catalogue endpoints — create, read, search, update, bulk import, and the lifecycle transitions
- Schemaless JSON storage in PostgreSQL (`jsonb`) with JSON-Schema payload validation per catalogue
- Elasticsearch-backed search with configurable index mappings per entity
- Redis caching for reads and search results with configurable TTL
- Externalized, per-entity Elasticsearch field configuration via `application.properties`
- Code-generated catalogues — new registries are scaffolded by a metaprogramming tool (`main.py`), not hand-written

## Tech Stack

- **Java 17 / Spring Boot 3.3.5**
- **PostgreSQL** — source-of-truth storage (documents held in a `jsonb` column)
- **Elasticsearch 8.13** — search index for catalogue records
- **Redis** — caching layer for reads and search results
- **Maven** — build tooling
- **Lombok** — boilerplate reduction

## Supported Catalogues

- **Organisation** (`org`) — organisations and their departments
- **User** (`user`) — users onboarded against an organisation

> More catalogues can be onboarded with the metaprogramming tool — see [Adding a Catalogue](#adding-a-catalogue).

## Getting Started

### Prerequisites

- Java 17+ (see `pom.xml`)
- Maven 3.9+ &nbsp;*(note: the `./mvnw` wrapper is not checked in — use a system `mvn`)*
- Docker + Docker Compose (provides PostgreSQL, Elasticsearch, and Redis)

### Configuration

Environment-specific values live in `src/main/resources/application.properties`:

- PostgreSQL connection — `spring.datasource.url/username/password` (defaults to `oas_db` / `oas_user`)
- Elasticsearch connection — `elasticsearch.host/port`
- Redis connection and cache TTL — `spring.redis.host/port`, `spring.redis.cacheTtl`
- Per-catalogue Elasticsearch field config — `elastic.required.field.<catalogue>.json.path`

The bundled `docker-compose.yml` starts PostgreSQL (5433), Redis (6379), and Elasticsearch (9200), plus pgAdmin (5050), Kibana (5601), and Redis Commander (8081).

### Build

```bash
mvn clean package
```

### Run

```bash
# 1. Start the backing services
docker compose up -d

# 2. Start the application (defaults to http://localhost:8080)
mvn spring-boot:run
```

Swagger UI is available at `http://localhost:8080/swagger-ui/index.html`.

### Seeding Catalogue Entities

A helper script creates the catalogue(s) in bulk via the metaprogramming tool:

```bash
./createEntities.sh
```

This loops through the entity list and invokes `python3 main.py --action create --name <name>` for each.

### Adding a Catalogue

New catalogues (registries) are generated from the templates in `registry_template/` by `main.py` — you do **not** hand-write the Java. See [Setup.MD](Setup.MD) for the full workflow. In short:

```bash
python3 main.py --action create --name <catalogue-name>   # requires: pip install pyfiglet
```

This scaffolds the controller/entity/repository/service slice (with the full maker-checker lifecycle endpoints) under `com.catalogue.verg.<name>`, creates the payload-validation and Elasticsearch field JSONs under `src/main/resources/`, and registers the catalogue in `Constants.java`, `application.properties`, and `VergProperties.java`. After generating, define the catalogue's schema in:

- `src/main/resources/payloadValidation/<name>PayloadValidation.json` — the JSON Schema (use **camelCase** field keys; the primary-key field carries `prefix`, `key: "Primary"`, `keyLength`)
- `src/main/resources/EsFieldsmapping/es<Name>RequiredFields.json` — the allowlist of fields to index into Elasticsearch

To remove a catalogue: `python3 main.py --action delete --name <catalogue-name>`.

## Record Lifecycle

Every catalogue record moves through a maker-checker lifecycle. The `status` is stored on the record and — with `createdOn` / `updatedOn` — indexed for search.

| Status | Meaning |
|--------|---------|
| `DRAFT` | Incomplete record saved via `draft` (relaxed validation) |
| `PENDING` | Submitted for approval (full validation) |
| `APPROVED` | Approved by the checker; awaiting final review |
| `ACTIVE` | Reviewed and published — live |
| `INACTIVE` | A live record toggled off |
| `REWORK` | Sent back for changes |
| `REJECTED` | Rejected |
| `DELETED` | Soft-deleted |

Typical flow: `create` / `add` → **PENDING** → `approve` → **APPROVED** → `review` → **ACTIVE**. A live record can be toggled `ACTIVE` ⇄ `INACTIVE`, and any non-deleted record can be soft-deleted (→ `DELETED`).

## API

All endpoints live under `/<catalogue>/v1/...` — `/org/v1/...` for organisations and `/user/v1/...` for users. Both catalogues expose the same set:

| Method | Path | Lifecycle | Description |
|--------|------|-----------|-------------|
| `POST` | `/v1/create` | → `PENDING` | Create a record for approval (full validation) |
| `POST` | `/v1/draft` | → `DRAFT` | Save an incomplete draft (relaxed validation) |
| `POST` | `/v1/add` | → `PENDING` | Create a record submitted for approval (full validation) |
| `PUT` | `/v1/add/{id}` | `DRAFT`/`REWORK` → `PENDING` | (Re-)submit an existing draft / rework record for approval |
| `PUT` | `/v1/approve` | `PENDING` → `APPROVED` / `REJECTED` / `REWORK` | Checker decision — body: `{ "id": "...", "status": "..." }` |
| `PUT` | `/v1/review` | `APPROVED` → `ACTIVE` / `REJECTED` / `REWORK` / `PENDING` | Final review / publish — same body shape |
| `PUT` | `/v1/toggle/{id}` | `ACTIVE` ⇄ `INACTIVE` | Toggle a live record on/off |
| `PUT` | `/v1/update/{id}` | — | Replace a record's payload (full validation) |
| `GET` | `/v1/read/{id}` | — | Read a record by id |
| `POST` | `/v1/search` | — | Search (Elasticsearch-backed; filter / query / facets) |
| `DELETE` | `/v1/delete/{id}` | → `DELETED` | Soft-delete a record |
| `POST` | `/v1/import` | → `PENDING` | Bulk import from an uploaded CSV/XLSX file (≤ 5 MB) |

Example — create an organisation (returns the record in `PENDING`, awaiting approval):

```bash
curl -s -X POST http://localhost:8080/org/v1/create \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Karnataka Department of Horticulture",
    "type": "Government Department",
    "location": "locmap-000000000001",
    "department": "Horticulture",
    "address": "Lalbagh Road, Bengaluru 560004",
    "contactNumber": "+91-80-26578184",
    "contactEmail": "horticulture@karnataka.gov.in"
  }'
```

Example — create a user (send `password` / `pin` as plaintext over TLS; the service hashes them, see [User credentials](#user-credentials)):

```bash
curl -s -X POST http://localhost:8080/user/v1/create \
  -H "Content-Type: application/json" \
  -d '{
    "firstName": "Asha",
    "lastName": "Rao",
    "email": "asha.rao@example.org",
    "phoneNumber": "+91-80-12345678",
    "orgId": "org-000000000001",
    "entityType": "FIELD_OFFICER",
    "password": "<plaintext-password>",
    "pin": "482913"
  }'
```

### User credentials

The user catalogue is the source of truth for user credentials, under these rules:

- Callers send `password` and `pin` as **plaintext**. The service BCrypt-hashes them on every write path (`create`, `update`, `draft`, `add`); do not pre-hash, or the stored value is a hash of a hash and login fails.
- The hashes live **only in PostgreSQL**. They are never indexed in Elasticsearch, never cached in Redis, and never returned by `read`, `search` or any write response.
- On `update`, `password` and `pin` are optional: an omitted or blank value keeps the stored hash, a supplied value replaces it.
- `POST /user/v1/verify` checks `{email, password}` against PostgreSQL and requires the record to be `ACTIVE`. It is called by the auth service and must not be exposed through the gateway.
- Authentication / IAM is handled by Keycloak via the auth service. This catalogue keeps the auth identity in step with the record: `create` provisions it, toggling to `INACTIVE` revokes it, toggling back to `ACTIVE` restores it, and `delete` removes it. Each call is made before the local write, and a failure aborts the operation. The generated `userId` is the identity surfaced into the JWT by the auth layer.

> **Upgrading an existing deployment:** earlier versions indexed the credential hashes. After deploying, run `POST /user/v1/loadFromPrimary` to rebuild the index without them, and flush the cached `user` entries in Redis.

## Project Structure

```
src/main/java/com/catalogue/verg/
├── core/                 # Shared, generic infrastructure (framework — do not hand-edit)
│   ├── cache/            # Redis cache service + config
│   ├── config/           # Jackson / app config
│   ├── dto/              # CustomResponse, LifecycleRequest, and shared DTOs
│   ├── elasticsearch/    # ES client, search service, search DTOs
│   ├── exception/        # Global REST exception handling
│   ├── logger/           # Logging helper
│   ├── service/          # Generic bulk-import service
│   └── util/             # Constants, PayloadValidation, PrimaryKeyUtil, LifecycleUtil, VergProperties, ...
├── org/                  # Organisation catalogue (generated via main.py)
│   ├── controller/       # OrgController — REST endpoints (lifecycle)
│   ├── entity/           # OrgEntity — JPA @Entity (jsonb data column)
│   ├── repository/       # OrgRepository
│   └── service/          # OrgService + impl/OrgServiceImpl
└── user/                 # User catalogue (generated via main.py)
    ├── controller/       # UserController — REST endpoints (lifecycle)
    ├── entity/           # UserEntity — JPA @Entity (jsonb data column)
    ├── repository/       # UserRepository
    └── service/          # UserService + impl/UserServiceImpl

src/main/resources/
├── application.properties   # DB / ES / Redis config + per-catalogue registration
├── payloadValidation/       # <catalogue>PayloadValidation.json — JSON Schema per catalogue
└── EsFieldsmapping/         # es<Catalogue>RequiredFields.json — ES field allowlist per catalogue
```

## Contributing

1. Fork the repository
2. Create a feature branch
3. Commit your changes
4. Open a pull request

**Conventions**

- `core/`, `main.py`, and `registry_template/` are finalized framework code — **do not hand-edit them.** Onboard new catalogues with `main.py` (see [Adding a Catalogue](#adding-a-catalogue)).
- Per-catalogue changes belong in the generated slice and its `payloadValidation` / `EsFieldsmapping` JSONs.
- Elasticsearch field keys must be **camelCase** — spaces or capitalized keys break ES.

## License

MIT License — see [LICENSE](LICENSE).
