# SQL

> **Audience:** anyone with a `psql` prompt against this database ·
> **Scope:** `docs/database/` — one file, [`khi-archive.sql`](./khi-archive.sql) ·
> **Source:** the initializer beans under `platform/config/` and `user/configs/`, the repository
> classes under `platform/repo/`, and the entity classes under `platform/model/` and `user/model/`

Every SQL statement the documentation refers to, in one file, in twelve numbered sections.
Nothing here is illustrative pseudo-SQL: each statement was either lifted from a `JdbcTemplate`
call in the Java source or written against the real column names in the entity classes, and each
section names the initializer bean or repository class it came from. PostgreSQL only — several
sections use `pg_trgm`, `information_schema` and `pg_catalog` features with no portable equivalent.

This folder is the **code**. The prose that explains it lives in
[`../internal/database/`](../internal/database/README.md) — start there if you want to understand
a table before you query it.

## Contents of [`khi-archive.sql`](./khi-archive.sql)

Sections 1–4 change the database. Sections 5–12 are read-only.

| § | Section | Reach for it when |
|---|---|---|
| 1 | Search indexes — `pg_trgm` GIN + btree `text_pattern_ops` | Search is slow, or you want the 204 indexes present before the app's first boot |
| 2 | Audit-log analytics indexes | An analytics endpoint got slow as the audit log grew — 33 indexes, three per `*_audit_logs` table |
| 3 | Enum `CHECK` constraint re-sync | You added an enum constant and inserts now fail against a constraint nobody edited |
| 4 | Idempotent backfills | You need one of the boot backfills applied without restarting: `version = 0`, `size` → `physical_size`, the per-user permission grants |
| 5 | Schema diagnostics | "Did that migration really run?" — what columns, indexes, extensions and `CHECK` constraints actually exist, plus an `EXPLAIN ANALYZE` template |
| 6 | Data integrity diagnostics | Rows with no file behind them, URLs from the wrong bucket, visibility drift, unfinished backfills, what is in the trash |
| 7 | Queries — media, projects, visibility | You are checking what an anonymous visitor can actually see |
| 8 | Queries — audit logs and analytics | You want an activity report the API does not expose, or the guest trending score |
| 9 | Queries — maqam vote panel and listen accountability | You need to know whether a vote was cast by someone who actually heard the recording |
| 10 | Queries — physical media inventory | You are reconciling the inventory against the source spreadsheet |
| 11 | Queries — guest corrections | A visitor's suggestion is stuck and you need to see where |
| 12 | Queries — tags and keywords | The autocomplete returns something you did not expect |

## How to run it

**Do not `\i` the whole file.** It is a reference, not a migration script — running it top to
bottom would apply four sections of DDL and then print eight result sets at you. Open the file,
find the section, read the comment above the block, run that block.

The two index sections are the exception, and this is the normal way to use them:

```sh
sed -n '/^-- 1\. SEARCH INDEXES/,/^-- 3\. ENUM/p' docs/database/khi-archive.sql \
  | psql "$DATABASE_URL"
```

## Before you run anything

- **Sections 1–3 duplicate work the app already does at boot.** Every statement is
  `IF NOT EXISTS` or `DROP … IF EXISTS`, so running them by hand is idempotent and converges on
  the same state a restart would. Reach for them when a restart is not available, or when you
  want the indexes built before the first request rather than during it.
- **`CREATE INDEX` takes an `ACCESS EXCLUSIVE` lock.** On a populated table use
  `CREATE INDEX CONCURRENTLY` instead — it cannot run inside a transaction block and it can leave
  an `INVALID` index behind if it fails. See
  [`../internal/database/migrations.md`](../internal/database/migrations.md) Recipe 5, and the
  invalid-index query in section 5.
- **Literals stand in for bind parameters.** The repository classes use `:q`, `:lim`,
  `:sevenDaysAgo` and friends; those are written out as literals and `NOW() - INTERVAL …` so the
  statements run as-is. Substitute your own values.
- **`removed_at IS NULL` means active, everywhere.** `DELETE` never removes a row in this schema —
  it stamps that column. Leave the predicate out and you are counting the trash.

## Related

- [`../internal/database/README.md`](../internal/database/README.md) — the prose: every table
  column by column, the conventions that repeat across them, indexes and performance, migrations
- [`../internal/database/migrations.md`](../internal/database/migrations.md) — how schema change
  works here: Hibernate `ddl-auto: update` plus hand-written initializer beans, and the recipes
- [`../internal/database/erd.md`](../internal/database/erd.md) — the tables and how they connect
- [`../diagrams/README.md`](../diagrams/README.md) — 44 diagrams, including 13 ER diagrams
- [`../internal/operations/configuration.md`](../internal/operations/configuration.md) — connection
  settings and what runs at startup
