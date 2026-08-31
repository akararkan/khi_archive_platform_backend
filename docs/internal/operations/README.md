# Operations

> **Audience:** Backend developers and operators ·
> **Scope:** runtime and deployment mechanics of `khi_archive_platform_backend`

This folder holds the four documents that describe how the application *runs* rather than what it
*exposes*: where every configuration value comes from, how the Caffeine cache layer is built and
evicted, how bytes move in and out of S3, and how an empty database gets filled with content. It
deliberately holds **no endpoint reference** — no paths, no request bodies, no authority strings.
Those live in the sibling folders ([`../content/`](../content/), [`../admin/`](../admin/),
[`../analytics/`](../analytics/), [`../specialised/`](../specialised/)) — and it holds **no
table-level schema**: columns, indexes, constraints and the startup SQL initializers are in
[`../database/`](../database/), which is this folder's other half. Everything here is part of
[`../`](../), the internal staff back-office plus database and operations documentation set, and
none of it is written for public consumption; the public and signed-in-visitor surface is
documented separately in [`../../external/`](../../external/).

## Contents

| File | What it covers | Read it when |
|---|---|---|
| [`caching.md`](./caching.md) | The in-process Caffeine layer: every cache region and its TTL, the `ReadCache`-per-entity pattern, where `evictAll()` is wired into each mutation, and how to trace a stale read | You saved an edit and the list endpoint still returns the old row; two instances disagree about the same record; a filter change did not take effect; or you are adding a `@Cacheable` and need to register its name in `CacheConfig` |
| [`configuration.md`](./configuration.md) | Every setting the app reads — the environment-variable reference, the `.env` file, an `application.yaml` walkthrough section by section, the two keys that break a live deployment, and how to build and run locally | You are bringing the app up on a new machine; an environment variable is not taking effect and you need to know which layer wins; startup failed and you want the known causes; or you are about to change `JWT_SECRET` or `spring.jpa.hibernate.ddl-auto` on something already running |
| [`seeding.md`](./seeding.md) | The two independent seed loaders — the in-process `SeedDataLoader` gated by `app.seed.load` and the `seed_via_rest.py` script that POSTs through the real `/bulk` endpoints — plus the root-level `test-*-1000.json` fixtures | You need a populated database for local work or pagination testing; you want audit rows and cache eviction to actually fire during a load; content appeared at startup that nobody asked for; or you are pointing an app at a database that must never be seeded |
| [`storage-and-media.md`](./storage-and-media.md) | S3 end to end: bucket and key layout, the upload path and multipart limits, why every archive byte is proxied instead of handed out as an S3 URL, Range and conditional-request handling, deletion semantics, and orphaned objects | A stream returns `404` or the wrong bytes; an upload fails on size; audio scrubbing or video seeking misbehaves; you need to know whether deleting a record also deleted the object; or you are estimating egress cost or auditing bucket public-access posture |

This folder has no subfolders — the four files above are its complete contents.

## Start here

1. [Configuration and Environment](./configuration.md) — get the app running and learn which
   settings exist before you change any of them.
2. [Migrations and startup initializers](../database/migrations.md) — how the schema comes to
   exist at all. There is no Flyway or Liquibase; read this before touching a database.
3. [Seed Data and Test Fixtures](./seeding.md) — put realistic content into the empty schema you
   just created.
4. [Object Storage and Media Handling](./storage-and-media.md) — where uploaded bytes go and why
   the browser never sees an S3 URL.
5. [Caching](./caching.md) — the layer that sits between the database and every list response, and
   the first suspect whenever a read looks wrong.

## Conventions

Know these before reading anything else in this folder. Each is documented in full at its link.

- **Auth** — JWT, read from `Authorization: Bearer <token>` first and from the HttpOnly
  `khi_auth_token` cookie only as a fallback; sessions are stateless and revocation is backed by
  the `sessions` table. See [`../02-authorization.md`](../02-authorization.md).
- **Authorities** — permissions are `<resource>:<action>` strings (`audio:read`, `maqam:vote`);
  ADMIN holds all of them through the role, EMPLOYEE and TEACHER hold an editable seeded grant set.
  See [`../02-authorization.md`](../02-authorization.md).
- **Error envelope** — every failure returns the same JSON shape with an `error` code; the two
  `@RestControllerAdvice` classes differ in places. See [`../03-errors.md`](../03-errors.md).
- **Pagination** — list endpoints return the standard Spring `Page` envelope; only `content[]`
  varies by endpoint. See [`../01-conventions.md#paged-responses`](../01-conventions.md#paged-responses).
- **`{{BASE_URL}}`** — every curl example uses this placeholder for the server origin
  (`http://localhost:8080` locally); no production hostname appears anywhere in these docs. See
  [`../00-overview.md#calling-the-api`](../00-overview.md#calling-the-api).
- **Serialization** — timestamps serialize in `Asia/Baghdad` and null fields are omitted from
  responses entirely. See
  [`../01-conventions.md#serialization-and-formats`](../01-conventions.md#serialization-and-formats).
- **No migration tool** — the schema evolves through Hibernate `ddl-auto=update` plus hand-written
  `JdbcTemplate` initializer beans that run on `ApplicationReadyEvent`. See
  [`../database/migrations.md`](../database/migrations.md).
- **Eight Hibernate tuning keys are inert** — `spring.jpa.hibernate.properties.hibernate.*` and
  `spring.jpa.jdbc.*` are not property paths Spring Boot binds, so batch fetching, JDBC batching
  and the UTC binding zone are all off. A live defect, not a documentation quirk. See
  [`./configuration.md#the-other-eight-keys-are-inert--verified`](./configuration.md#the-other-eight-keys-are-inert--verified).
- **The cache is Caffeine, not Redis** — several javadoc comments in the source still say "Redis";
  they are historical and wrong. See [`./caching.md`](./caching.md).
- **Media bytes are always proxied** — no presigned URL is generated anywhere, and no S3 URL for
  archive media reaches a browser. See [`./storage-and-media.md`](./storage-and-media.md).

## Related

- [`../README.md`](../README.md) — the internal documentation index, one level up.
- [`../00-overview.md`](../00-overview.md) — what the internal surface is, who calls it, and the
  full controller inventory.
- [`../database/README.md`](../database/README.md) — the closest sibling folder and the other half
  of "database and operations": table-by-table schema, the ERD, indexes and performance, and the
  startup SQL that runs before any cache is populated.
- [`../../external/07-streaming.md`](../../external/07-streaming.md) — the public half of the byte
  proxy described in [`./storage-and-media.md`](./storage-and-media.md).
- [`../../legacy/README.md`](../../legacy/README.md) — the superseded root-level feature notes
  (media URL guide, private media streaming, and similar) that these documents replace.
