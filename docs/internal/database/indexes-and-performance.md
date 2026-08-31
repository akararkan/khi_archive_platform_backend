# Indexes and Query Performance

> **Audience:** Backend engineers and operators ·
> **Source:** `src/main/java/ak/dev/khi_archive_platform/platform/config/AuditLogIndexInitializer.java`,
> `CategorySearchIndexInitializer.java`, `MediaSearchIndexInitializer.java`,
> `PersonSearchIndexInitializer.java`, `CacheConfig.java`,
> `src/main/resources/application.yaml`, `platform/model/**`, `platform/repo/**`

Every index this application relies on, where it is created, and which query it exists for.
There is no Flyway or Liquibase in this project, so indexes arrive from exactly two places:

1. **JPA declarations** — `@Table(indexes = { @Index(...) })` on entity classes, emitted by
   Hibernate under `spring.jpa.hibernate.ddl-auto=update`.
2. **Startup initializers** — four `@Component` beans in `platform/config/` that execute raw
   `CREATE INDEX IF NOT EXISTS` through `JdbcTemplate` after the context is ready. Everything
   Postgres-specific lives here, because JPA has no annotation for a GIN index, an operator
   class, or an expression index.

## Contents

| Section | What it covers |
|---|---|
| [How indexes get created](#how-indexes-get-created) | The four initializers, their trigger, their failure mode |
| [The `pg_trgm` extension](#the-pg_trgm-extension) | Where it is enabled and what it provides |
| [Two-phase fuzzy search](#two-phase-fuzzy-search) | The query shape the GIN indexes exist for |
| [Index inventory](#index-inventory) | Complete list, per initializer, plus entity-declared indexes |
| [Hibernate tuning](#hibernate-tuning-in-applicationyaml) | Batch fetch, JDBC batching, `open-in-view` — and the eight keys that are bound to non-existent paths and do nothing |
| [Caffeine read-cache layer](#the-caffeine-read-cache-layer) | Every cache, its contents, its eviction trigger |
| [Diagnosing a slow endpoint](#diagnosing-a-slow-endpoint) | Runbook |

---

## How indexes get created

| Initializer | Trigger | Creates | Also does |
|---|---|---|---|
| `MediaSearchIndexInitializer` | `@EventListener(ApplicationReadyEvent.class)` | `pg_trgm` + 191 indexes on `images`, `texts`, `videos`, `audios` and their child collection tables | Drops five stale `*_audit_logs_action_check` constraints; backfills `version = 0` where NULL |
| `CategorySearchIndexInitializer` | `@EventListener(ApplicationReadyEvent.class)` | `pg_trgm` + 3 GIN indexes on `categories` / `category_keywords` | Drops `category_audit_logs_action_check` |
| `PersonSearchIndexInitializer` | `@EventListener(ApplicationReadyEvent.class)` | `pg_trgm` + 10 GIN indexes on `person` / `person_person_type` | Drops `person_audit_logs_action_check` |
| `AuditLogIndexInitializer` | `@EventListener(ApplicationReadyEvent.class)` | 33 btree indexes — 3 per `*_audit_logs` table across 11 tables | — |

All four run on `ApplicationReadyEvent`, i.e. **after** Hibernate's `ddl-auto=update` pass, so the
tables normally exist by the time the `CREATE INDEX` fires. Every `CREATE` statement is
`IF NOT EXISTS` and every constraint drop is `DROP CONSTRAINT IF EXISTS`, so the beans are
idempotent across restarts. (The one non-DDL statement — the `UPDATE … SET version = 0 WHERE
version IS NULL` backfill — is idempotent for the same reason: after the first run it matches no
rows.)

Failures are swallowed and logged rather than propagated. `MediaSearchIndexInitializer` and
`AuditLogIndexInitializer` wrap **each** statement in its own `try`, via per-statement helpers;
`CategorySearchIndexInitializer` and `PersonSearchIndexInitializer` wrap the extension plus all
their index statements in **one** `try`, so a failure on the first index skips the rest of that
group. `AuditLogIndexInitializer.createIndex` is representative:

```java
private void createIndex(String name, String tail) {
    try {
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS " + name + " " + tail);
    } catch (Exception e) {
        // Table may not exist yet on first boot before Hibernate creates it.
        log.warn("Skipped index {}: {}", name, e.getMessage());
    }
}
```

**Operational consequences of that design:**

- A missing index **never fails the boot**. It logs at `WARN` (`Skipped index …`,
  `Failed to create trigram index … on …`) and the application serves the query with a sequential
  scan instead. Grep the startup log for `Skipped index` / `Failed to create` before blaming the
  query.
- On a brand-new database, the very first boot can legitimately skip indexes whose table Hibernate
  had not created yet. The second boot creates them. After a first-ever deploy, restart once and
  re-read the log.
- These are plain `CREATE INDEX`, **not** `CREATE INDEX CONCURRENTLY`. Postgres takes a lock that
  blocks writes to the target table for the duration of the build. Existing indexes are skipped
  instantly by `IF NOT EXISTS`, so this only matters the first time a newly added index is built
  against an already-large table — that build happens inside the startup window.
- The sibling `*ConstraintInitializer` / `*Seeder` / `*MigrationInitializer` beans in the same
  package do **not** create indexes; see the migrations/initializers doc in this folder.

Success lines to look for on a healthy boot — seven `INFO` lines, one per index group:

```text
Image search indexes ensured (GIN trgm + btree text_pattern_ops on every searchable column + child tables)
Text search indexes ensured (GIN trgm + btree text_pattern_ops on every searchable column + child tables)
Video search indexes ensured (GIN trgm + btree text_pattern_ops on every searchable column + child tables)
Audio search indexes ensured (GIN trgm + btree text_pattern_ops on every searchable column + child tables)
Category search indexes ensured (pg_trgm GIN on name, description, keywords)
Person search indexes ensured (pg_trgm GIN on names, places, tags, keywords, types)
Audit-log analytics indexes ensured on 11 tables
```

---

## The `pg_trgm` extension

Three of the four initializers enable it, with the identical statement:

```java
jdbcTemplate.execute("CREATE EXTENSION IF NOT EXISTS pg_trgm");
```

| Class | Behavior if the `CREATE EXTENSION` fails |
|---|---|
| `MediaSearchIndexInitializer` | Logs `Failed to ensure pg_trgm extension: …` and **`return`s immediately** — none of the 191 media indexes, none of the constraint drops, and no version backfill run this boot |
| `CategorySearchIndexInitializer` | Caught by the same `try` as the three index statements; logs `Failed to ensure category search indexes: …` |
| `PersonSearchIndexInitializer` | Caught by the same `try` as the ten index statements; logs `Failed to ensure person search indexes: …` |

`PersonSearchIndexInitializer` documents why the duplication is safe:

```java
// pg_trgm is created by CategorySearchIndexInitializer too — CREATE EXTENSION
// IF NOT EXISTS is idempotent, so calling it again is harmless.
```

`CREATE EXTENSION` needs privileges the application's database role may not hold on a managed
Postgres instance. A missing extension is not a "slow search" failure mode: `%` and `similarity()`
are supplied by `pg_trgm`, so every query that uses them errors outright. The search endpoints fail
loudly rather than degrading.

**What the extension provides, and which of it this codebase uses:**

| Feature | Used by |
|---|---|
| `gin_trgm_ops` operator class | Every `CREATE INDEX … USING GIN (LOWER(col) gin_trgm_ops)` in all three search initializers |
| `%` similarity operator | The fuzzy leg of every media candidate CTE: `LOWER(COALESCE(e.col, '')) % LOWER(:qRaw)` |
| `similarity(a, b)` function | The tier-3 ranking expression in every search query, and the `similarity(...) > :threshold` predicate in the category / person / project queries |
| `pg_trgm.similarity_threshold` GUC | Governs `%`. Never set by this application — `_Not documented in source._` The explicit `similarity() > :threshold` predicates use application constants instead (see below) |

A GIN trigram index stores, for each distinct three-character sequence in the indexed expression, a
posting list of the rows containing it. A `LIKE '%abc%'` or `col % 'abc'` is decomposed into its
trigrams, the posting lists are intersected into a bitmap, and only the surviving heap pages are
read and rechecked. That turns an unanchored substring match — normally the worst case for an index
— into a bitmap index scan.

**The 3-character floor.** A query shorter than three characters produces no complete trigram, so
the GIN index cannot contribute and Postgres falls back to a full scan. That is exactly why
`MediaSearchIndexInitializer` also builds a btree on `LOWER(col) text_pattern_ops` for the primary
columns:

```java
/**
 * Btree index on {@code LOWER(col) text_pattern_ops}. Required for prefix
 * LIKE (`LIKE 'q%'`) to be index-driven for *any* query length — including
 * 1-2 characters where the GIN trigram index can't help. With this index
 * present, a search for "ha" against a 30TB table is sub-millisecond on
 * the column lookup.
 */
```

`text_pattern_ops` makes a btree usable for `LIKE 'prefix%'` regardless of the database collation.
It does **not** help `LIKE '%substring%'` — that is the GIN index's job. The two index families are
complementary, which is why the primary columns carry both.

---

## Two-phase fuzzy search

Three distinct query families run against these indexes.

### Family A — media search (audio, video, image, text)

Two implementations of the same algorithm are both live:

| Path | Implementation | Prefilter cap |
|---|---|---|
| Staff `/api/{audio,video,image,text}/search` | `MediaSearchSqlBuilder.build(SPEC, tokens, prefilter, limit)` — SQL assembled per request so a multi-word query becomes one CTE per token | `SEARCH_PREFILTER_LIMIT = 2000` (constant in `AudioService`, `VideoService`, `ImageService`, `TextService`) |
| Guest `/api/guest/**` search | The static native `@Query` on `AudioRepository` / `VideoRepository` / `ImageRepository` / `TextRepository` `searchByText(...)` — single token, hand-written | `GuestSearchService.PREFILTER_LIMIT = 5000` |

**Phase 1 — bounded candidate generation.** Per token, one CTE ORs three probes against every
searchable column, then `UNION`s one leg per child collection table, then caps itself:

```sql
WITH cands AS (
    SELECT i.id
      FROM images i
     WHERE i.removed_at IS NULL
       AND (
            LOWER(COALESCE(i.image_code, ''))                  LIKE LOWER(:q) || '%' ESCAPE '\'
         OR LOWER(COALESCE(i.image_code, ''))                  LIKE '%' || LOWER(:q) || '%' ESCAPE '\'
         ...
         OR LOWER(COALESCE(i.original_title, '')) % LOWER(:qRaw)
         ...
       )
    UNION
    SELECT t.image_id FROM image_tags     t WHERE LOWER(t.tag)     LIKE LOWER(:q) || '%' ESCAPE '\' OR LOWER(t.tag)     LIKE '%' || LOWER(:q) || '%' ESCAPE '\' OR LOWER(t.tag)     % LOWER(:qRaw)
    UNION
    SELECT k.image_id FROM image_keywords k WHERE LOWER(k.keyword) LIKE LOWER(:q) || '%' ESCAPE '\' OR LOWER(k.keyword) LIKE '%' || LOWER(:q) || '%' ESCAPE '\' OR LOWER(k.keyword) % LOWER(:qRaw)
    LIMIT :prefilter
)
```

The three probes map one-to-one onto the three index families:

| Probe | Index that serves it | Works at query length |
|---|---|---|
| `LIKE LOWER(:q) \|\| '%'` (prefix) | `*_pat` btree on `LOWER(col) text_pattern_ops` | 1 char and up |
| `LIKE '%' \|\| LOWER(:q) \|\| '%'` (substring) | `*_trgm` GIN on `LOWER(col) gin_trgm_ops` | 3 chars and up |
| `col % :qRaw` (fuzzy / typo-tolerant) | `*_trgm` GIN on `LOWER(col) gin_trgm_ops` | 3 chars and up |

`MediaSearchSqlBuilder`'s own contract states why the CTE is capped:

```java
 *       against every searchable column on the entity AND every child
 *       collection table. Each CTE is bounded by {@code prefilter} so worst-
 *       case work is fixed regardless of table size.
```

**Phase 2 — join and rank.** The per-token CTEs are inner-joined back to the entity table (that
inner join **is** the AND across tokens: a row survives only if every token matched somewhere), then
ordered by three tiers, cheapest first:

1. count of tokens that prefix-match a primary column,
2. count of tokens that substring-match a primary column,
3. `GREATEST(similarity(...))` across primary columns plus a `MAX(similarity(...))` correlated
   subquery per child table.

Tier 3 is the expensive tier — it calls `similarity()` per surviving row and runs one correlated
subquery per child collection. It is affordable only because phase 1 already bounded the row count
to `prefilter`. The child-table FK btrees (`idx_image_tags_image_id`, `idx_audio_keywords_audio_id`,
…) exist specifically so those correlated subqueries are index lookups rather than scans.

### Family B — category, person, project

Single-pass: `LIKE` legs OR'd with explicit `similarity(...) > :threshold` legs, ordered by
`GREATEST(similarity(...))`. No CTE, no prefilter — the row counts are small.

| Caller | Threshold constant | Value |
|---|---|---|
| `CategoryService` | `SEARCH_SIMILARITY_THRESHOLD` | `0.3` |
| `PersonService` | `SEARCH_SIMILARITY_THRESHOLD` | `0.3` |
| `GuestSearchService` (project, category, person) | `SIMILARITY_THRESHOLD` | `0.2` |

Only two of the three entities in this family are index-backed. `CategorySearchIndexInitializer` covers `categories` /
`category_keywords` and `PersonSearchIndexInitializer` covers `person` / `person_person_type`, but
**no initializer creates a trigram index for `projects`, `project_tags` or `project_keywords`** —
`ProjectRepository.searchByText` runs the same `LIKE '%q%'` + `similarity(...) > :threshold` shape
against `project_name`, `project_code`, `description` and the two child tables with nothing but the
entity-declared btrees (`idx_project_code`, `idx_project_person_id`, `idx_project_removed_at`)
present, and those serve none of its predicates. The project leg is a sequential scan by
construction; it is affordable only because the project table is small.

### Family C — physical media

`PhysicalMediaRepository.searchByText` uses plain substring `LIKE` with **no pg_trgm at all**, as
its own javadoc says:

```java
 * Cheap full-text-ish search over the columns end-users actually care
 * about. Kept native so it can use the indexes on physical_label /
 * media_type and stay readable; no pg_trgm dependency for this entity.
```

The predicates are `LOWER(COALESCE(p.physical_label, '')) LIKE '%' || LOWER(:q) || '%'`. A
leading-wildcard pattern over a `LOWER(...)` expression cannot use the plain btrees
`idx_pm_physical_label` / `idx_pm_media_type`, which index the raw column values. Confirm the actual
plan with `EXPLAIN ANALYZE` before assuming either way — see the runbook. This is acceptable today
because the inventory is a few thousand rows; it will not stay acceptable at media scale.

### Verifying expression-index matching

The initializers index the expression `LOWER(col)`. Several query legs test
`LOWER(COALESCE(col, ''))`. Postgres matches an expression index by comparing expression trees, so
the two forms are not automatically interchangeable. Note the deliberate contrast inside a single
query in `PersonRepository.searchByText` — the non-nullable column is written bare, the nullable
ones are wrapped:

```sql
LOWER(p.full_name) LIKE LOWER(CONCAT('%', :q, '%'))
OR LOWER(COALESCE(p.nickname, '')) LIKE LOWER(CONCAT('%', :q, '%'))
```

Before concluding that a search index "is not working", run `EXPLAIN ANALYZE` on the real statement
and read which index the planner actually chose. This is step 4 of the runbook and it is the single
highest-value check on this codebase.

---

## Index inventory

### 1. Media search indexes — `MediaSearchIndexInitializer`

191 indexes across four entity tables and their child collection tables. Three helper methods
generate every one of them; the SQL below is verbatim:

```java
// createTrgmIndex(indexName, table, column)
"CREATE INDEX IF NOT EXISTS " + indexName
        + " ON " + table + " USING GIN (LOWER(" + column + ") gin_trgm_ops)"

// createBtreeIndex(indexName, table, column)
"CREATE INDEX IF NOT EXISTS " + indexName
        + " ON " + table + " (" + column + ")"

// createBtreePatternIndex(indexName, table, column)
"CREATE INDEX IF NOT EXISTS " + indexName
        + " ON " + table + " (LOWER(" + column + ") text_pattern_ops)"
```

#### Image — 48 indexes (29 GIN trgm, 19 btree)

Serves `ImageRepository.searchByText` (guest) / `IMAGE_SEARCH_SPEC` via `MediaSearchSqlBuilder` (staff).

| Index | Table | Columns / expression | Type | Query leg it serves |
|---|---|---|---|---|
| `idx_images_image_code_trgm` | `images` | `LOWER(image_code) gin_trgm_ops` | GIN trgm | substring `LIKE '%q%'` and fuzzy `% :qRaw` legs on `images.image_code` |
| `idx_images_file_name_trgm` | `images` | `LOWER(file_name) gin_trgm_ops` | GIN trgm | substring `LIKE '%q%'` and fuzzy `% :qRaw` legs on `images.file_name` |
| `idx_images_volume_name_trgm` | `images` | `LOWER(volume_name) gin_trgm_ops` | GIN trgm | substring `LIKE '%q%'` and fuzzy `% :qRaw` legs on `images.volume_name` |
| `idx_images_directory_trgm` | `images` | `LOWER(directory) gin_trgm_ops` | GIN trgm | substring `LIKE '%q%'` and fuzzy `% :qRaw` legs on `images.directory` |
| `idx_images_path_external_trgm` | `images` | `LOWER(path_in_external_volume) gin_trgm_ops` | GIN trgm | substring `LIKE '%q%'` and fuzzy `% :qRaw` legs on `images.path_in_external_volume` |
| `idx_images_auto_path_trgm` | `images` | `LOWER(auto_path) gin_trgm_ops` | GIN trgm | substring `LIKE '%q%'` and fuzzy `% :qRaw` legs on `images.auto_path` |
| `idx_images_original_title_trgm` | `images` | `LOWER(original_title) gin_trgm_ops` | GIN trgm | substring `LIKE '%q%'` and fuzzy `% :qRaw` legs on `images.original_title` |
| `idx_images_alternative_title_trgm` | `images` | `LOWER(alternative_title) gin_trgm_ops` | GIN trgm | substring `LIKE '%q%'` and fuzzy `% :qRaw` legs on `images.alternative_title` |
| `idx_images_central_kurdish_title_trgm` | `images` | `LOWER(title_in_central_kurdish) gin_trgm_ops` | GIN trgm | substring `LIKE '%q%'` and fuzzy `% :qRaw` legs on `images.title_in_central_kurdish` |
| `idx_images_romanized_title_trgm` | `images` | `LOWER(romanized_title) gin_trgm_ops` | GIN trgm | substring `LIKE '%q%'` and fuzzy `% :qRaw` legs on `images.romanized_title` |
| `idx_images_form_trgm` | `images` | `LOWER(form) gin_trgm_ops` | GIN trgm | substring `LIKE '%q%'` and fuzzy `% :qRaw` legs on `images.form` |
| `idx_images_event_trgm` | `images` | `LOWER(event) gin_trgm_ops` | GIN trgm | substring `LIKE '%q%'` and fuzzy `% :qRaw` legs on `images.event` |
| `idx_images_location_trgm` | `images` | `LOWER(location) gin_trgm_ops` | GIN trgm | substring `LIKE '%q%'` and fuzzy `% :qRaw` legs on `images.location` |
| `idx_images_description_trgm` | `images` | `LOWER(description) gin_trgm_ops` | GIN trgm | substring `LIKE '%q%'` and fuzzy `% :qRaw` legs on `images.description` |
| `idx_images_person_shown_trgm` | `images` | `LOWER(person_shown_in_image) gin_trgm_ops` | GIN trgm | substring `LIKE '%q%'` and fuzzy `% :qRaw` legs on `images.person_shown_in_image` |
| `idx_images_creator_trgm` | `images` | `LOWER(creator_artist_photographer) gin_trgm_ops` | GIN trgm | substring `LIKE '%q%'` and fuzzy `% :qRaw` legs on `images.creator_artist_photographer` |
| `idx_images_contributor_trgm` | `images` | `LOWER(contributor) gin_trgm_ops` | GIN trgm | substring `LIKE '%q%'` and fuzzy `% :qRaw` legs on `images.contributor` |
| `idx_images_provenance_trgm` | `images` | `LOWER(provenance) gin_trgm_ops` | GIN trgm | substring `LIKE '%q%'` and fuzzy `% :qRaw` legs on `images.provenance` |
| `idx_images_photostory_trgm` | `images` | `LOWER(photostory) gin_trgm_ops` | GIN trgm | substring `LIKE '%q%'` and fuzzy `% :qRaw` legs on `images.photostory` |
| `idx_images_archive_cataloging_trgm` | `images` | `LOWER(archive_cataloging) gin_trgm_ops` | GIN trgm | substring `LIKE '%q%'` and fuzzy `% :qRaw` legs on `images.archive_cataloging` |
| `idx_images_physical_label_trgm` | `images` | `LOWER(physical_label) gin_trgm_ops` | GIN trgm | substring `LIKE '%q%'` and fuzzy `% :qRaw` legs on `images.physical_label` |
| `idx_images_location_in_archive_trgm` | `images` | `LOWER(location_in_archive_room) gin_trgm_ops` | GIN trgm | substring `LIKE '%q%'` and fuzzy `% :qRaw` legs on `images.location_in_archive_room` |
| `idx_images_note_trgm` | `images` | `LOWER(note) gin_trgm_ops` | GIN trgm | substring `LIKE '%q%'` and fuzzy `% :qRaw` legs on `images.note` |
| `idx_images_image_code_pat` | `images` | `LOWER(image_code) text_pattern_ops` | btree | prefix `LIKE 'q%'` leg on `images.image_code` (works at 1–2 chars) |
| `idx_images_file_name_pat` | `images` | `LOWER(file_name) text_pattern_ops` | btree | prefix `LIKE 'q%'` leg on `images.file_name` (works at 1–2 chars) |
| `idx_images_original_title_pat` | `images` | `LOWER(original_title) text_pattern_ops` | btree | prefix `LIKE 'q%'` leg on `images.original_title` (works at 1–2 chars) |
| `idx_images_alternative_title_pat` | `images` | `LOWER(alternative_title) text_pattern_ops` | btree | prefix `LIKE 'q%'` leg on `images.alternative_title` (works at 1–2 chars) |
| `idx_images_central_kurdish_title_pat` | `images` | `LOWER(title_in_central_kurdish) text_pattern_ops` | btree | prefix `LIKE 'q%'` leg on `images.title_in_central_kurdish` (works at 1–2 chars) |
| `idx_images_romanized_title_pat` | `images` | `LOWER(romanized_title) text_pattern_ops` | btree | prefix `LIKE 'q%'` leg on `images.romanized_title` (works at 1–2 chars) |
| `idx_images_creator_pat` | `images` | `LOWER(creator_artist_photographer) text_pattern_ops` | btree | prefix `LIKE 'q%'` leg on `images.creator_artist_photographer` (works at 1–2 chars) |
| `idx_images_person_shown_pat` | `images` | `LOWER(person_shown_in_image) text_pattern_ops` | btree | prefix `LIKE 'q%'` leg on `images.person_shown_in_image` (works at 1–2 chars) |
| `idx_images_event_pat` | `images` | `LOWER(event) text_pattern_ops` | btree | prefix `LIKE 'q%'` leg on `images.event` (works at 1–2 chars) |
| `idx_image_subjects_subject_trgm` | `image_subjects` | `LOWER(subject) gin_trgm_ops` | GIN trgm | substring `LIKE '%q%'` and fuzzy `% :qRaw` legs on `image_subjects.subject` |
| `idx_image_genres_genre_trgm` | `image_genres` | `LOWER(genre) gin_trgm_ops` | GIN trgm | substring `LIKE '%q%'` and fuzzy `% :qRaw` legs on `image_genres.genre` |
| `idx_image_colors_color_trgm` | `image_colors` | `LOWER(color) gin_trgm_ops` | GIN trgm | substring `LIKE '%q%'` and fuzzy `% :qRaw` legs on `image_colors.color` |
| `idx_image_usages_usage_trgm` | `image_usages` | `LOWER(usage_context) gin_trgm_ops` | GIN trgm | substring `LIKE '%q%'` and fuzzy `% :qRaw` legs on `image_usages.usage_context` |
| `idx_image_tags_tag_trgm` | `image_tags` | `LOWER(tag) gin_trgm_ops` | GIN trgm | substring `LIKE '%q%'` and fuzzy `% :qRaw` legs on `image_tags.tag` |
| `idx_image_keywords_keyword_trgm` | `image_keywords` | `LOWER(keyword) gin_trgm_ops` | GIN trgm | substring `LIKE '%q%'` and fuzzy `% :qRaw` legs on `image_keywords.keyword` |
| `idx_image_subjects_subject_pat` | `image_subjects` | `LOWER(subject) text_pattern_ops` | btree | prefix `LIKE 'q%'` leg on `image_subjects.subject` (works at 1–2 chars) |
| `idx_image_genres_genre_pat` | `image_genres` | `LOWER(genre) text_pattern_ops` | btree | prefix `LIKE 'q%'` leg on `image_genres.genre` (works at 1–2 chars) |
| `idx_image_tags_tag_pat` | `image_tags` | `LOWER(tag) text_pattern_ops` | btree | prefix `LIKE 'q%'` leg on `image_tags.tag` (works at 1–2 chars) |
| `idx_image_keywords_keyword_pat` | `image_keywords` | `LOWER(keyword) text_pattern_ops` | btree | prefix `LIKE 'q%'` leg on `image_keywords.keyword` (works at 1–2 chars) |
| `idx_image_subjects_image_id` | `image_subjects` | `image_id` | btree | child-table join / phase-2 `similarity()` subquery keyed on `image_subjects.image_id` |
| `idx_image_genres_image_id` | `image_genres` | `image_id` | btree | child-table join / phase-2 `similarity()` subquery keyed on `image_genres.image_id` |
| `idx_image_colors_image_id` | `image_colors` | `image_id` | btree | child-table join / phase-2 `similarity()` subquery keyed on `image_colors.image_id` |
| `idx_image_usages_image_id` | `image_usages` | `image_id` | btree | child-table join / phase-2 `similarity()` subquery keyed on `image_usages.image_id` |
| `idx_image_tags_image_id` | `image_tags` | `image_id` | btree | child-table join / phase-2 `similarity()` subquery keyed on `image_tags.image_id` |
| `idx_image_keywords_image_id` | `image_keywords` | `image_id` | btree | child-table join / phase-2 `similarity()` subquery keyed on `image_keywords.image_id` |

#### Text — 42 indexes (26 GIN trgm, 16 btree)

Serves `TextRepository.searchByText` (guest) / `TEXT_SEARCH_SPEC` via `MediaSearchSqlBuilder` (staff).

| Index | Table | Columns / expression | Type | Query leg it serves |
|---|---|---|---|---|
| `idx_texts_text_code_trgm` | `texts` | `LOWER(text_code) gin_trgm_ops` | GIN trgm | substring `LIKE '%q%'` and fuzzy `% :qRaw` legs on `texts.text_code` |
| `idx_texts_file_name_trgm` | `texts` | `LOWER(file_name) gin_trgm_ops` | GIN trgm | substring `LIKE '%q%'` and fuzzy `% :qRaw` legs on `texts.file_name` |
| `idx_texts_volume_name_trgm` | `texts` | `LOWER(volume_name) gin_trgm_ops` | GIN trgm | substring `LIKE '%q%'` and fuzzy `% :qRaw` legs on `texts.volume_name` |
| `idx_texts_directory_trgm` | `texts` | `LOWER(directory) gin_trgm_ops` | GIN trgm | substring `LIKE '%q%'` and fuzzy `% :qRaw` legs on `texts.directory` |
| `idx_texts_path_external_trgm` | `texts` | `LOWER(path_in_external_volume) gin_trgm_ops` | GIN trgm | substring `LIKE '%q%'` and fuzzy `% :qRaw` legs on `texts.path_in_external_volume` |
| `idx_texts_auto_path_trgm` | `texts` | `LOWER(auto_path) gin_trgm_ops` | GIN trgm | substring `LIKE '%q%'` and fuzzy `% :qRaw` legs on `texts.auto_path` |
| `idx_texts_original_title_trgm` | `texts` | `LOWER(original_title) gin_trgm_ops` | GIN trgm | substring `LIKE '%q%'` and fuzzy `% :qRaw` legs on `texts.original_title` |
| `idx_texts_alternative_title_trgm` | `texts` | `LOWER(alternative_title) gin_trgm_ops` | GIN trgm | substring `LIKE '%q%'` and fuzzy `% :qRaw` legs on `texts.alternative_title` |
| `idx_texts_central_kurdish_title_trgm` | `texts` | `LOWER(title_in_central_kurdish) gin_trgm_ops` | GIN trgm | substring `LIKE '%q%'` and fuzzy `% :qRaw` legs on `texts.title_in_central_kurdish` |
| `idx_texts_romanized_title_trgm` | `texts` | `LOWER(romanized_title) gin_trgm_ops` | GIN trgm | substring `LIKE '%q%'` and fuzzy `% :qRaw` legs on `texts.romanized_title` |
| `idx_texts_document_type_trgm` | `texts` | `LOWER(document_type) gin_trgm_ops` | GIN trgm | substring `LIKE '%q%'` and fuzzy `% :qRaw` legs on `texts.document_type` |
| `idx_texts_description_trgm` | `texts` | `LOWER(description) gin_trgm_ops` | GIN trgm | substring `LIKE '%q%'` and fuzzy `% :qRaw` legs on `texts.description` |
| `idx_texts_script_trgm` | `texts` | `LOWER(script) gin_trgm_ops` | GIN trgm | substring `LIKE '%q%'` and fuzzy `% :qRaw` legs on `texts.script` |
| `idx_texts_transcription_trgm` | `texts` | `LOWER(transcription) gin_trgm_ops` | GIN trgm | substring `LIKE '%q%'` and fuzzy `% :qRaw` legs on `texts.transcription` |
| `idx_texts_isbn_trgm` | `texts` | `LOWER(isbn) gin_trgm_ops` | GIN trgm | substring `LIKE '%q%'` and fuzzy `% :qRaw` legs on `texts.isbn` |
| `idx_texts_language_trgm` | `texts` | `LOWER(language) gin_trgm_ops` | GIN trgm | substring `LIKE '%q%'` and fuzzy `% :qRaw` legs on `texts.language` |
| `idx_texts_dialect_trgm` | `texts` | `LOWER(dialect) gin_trgm_ops` | GIN trgm | substring `LIKE '%q%'` and fuzzy `% :qRaw` legs on `texts.dialect` |
| `idx_texts_author_trgm` | `texts` | `LOWER(author) gin_trgm_ops` | GIN trgm | substring `LIKE '%q%'` and fuzzy `% :qRaw` legs on `texts.author` |
| `idx_texts_contributors_trgm` | `texts` | `LOWER(contributors) gin_trgm_ops` | GIN trgm | substring `LIKE '%q%'` and fuzzy `% :qRaw` legs on `texts.contributors` |
| `idx_texts_printing_house_trgm` | `texts` | `LOWER(printing_house) gin_trgm_ops` | GIN trgm | substring `LIKE '%q%'` and fuzzy `% :qRaw` legs on `texts.printing_house` |
| `idx_texts_provenance_trgm` | `texts` | `LOWER(provenance) gin_trgm_ops` | GIN trgm | substring `LIKE '%q%'` and fuzzy `% :qRaw` legs on `texts.provenance` |
| `idx_texts_note_trgm` | `texts` | `LOWER(note) gin_trgm_ops` | GIN trgm | substring `LIKE '%q%'` and fuzzy `% :qRaw` legs on `texts.note` |
| `idx_texts_text_code_pat` | `texts` | `LOWER(text_code) text_pattern_ops` | btree | prefix `LIKE 'q%'` leg on `texts.text_code` (works at 1–2 chars) |
| `idx_texts_file_name_pat` | `texts` | `LOWER(file_name) text_pattern_ops` | btree | prefix `LIKE 'q%'` leg on `texts.file_name` (works at 1–2 chars) |
| `idx_texts_original_title_pat` | `texts` | `LOWER(original_title) text_pattern_ops` | btree | prefix `LIKE 'q%'` leg on `texts.original_title` (works at 1–2 chars) |
| `idx_texts_alternative_title_pat` | `texts` | `LOWER(alternative_title) text_pattern_ops` | btree | prefix `LIKE 'q%'` leg on `texts.alternative_title` (works at 1–2 chars) |
| `idx_texts_central_kurdish_title_pat` | `texts` | `LOWER(title_in_central_kurdish) text_pattern_ops` | btree | prefix `LIKE 'q%'` leg on `texts.title_in_central_kurdish` (works at 1–2 chars) |
| `idx_texts_romanized_title_pat` | `texts` | `LOWER(romanized_title) text_pattern_ops` | btree | prefix `LIKE 'q%'` leg on `texts.romanized_title` (works at 1–2 chars) |
| `idx_texts_author_pat` | `texts` | `LOWER(author) text_pattern_ops` | btree | prefix `LIKE 'q%'` leg on `texts.author` (works at 1–2 chars) |
| `idx_texts_isbn_pat` | `texts` | `LOWER(isbn) text_pattern_ops` | btree | prefix `LIKE 'q%'` leg on `texts.isbn` (works at 1–2 chars) |
| `idx_text_subjects_subject_trgm` | `text_subjects` | `LOWER(subject) gin_trgm_ops` | GIN trgm | substring `LIKE '%q%'` and fuzzy `% :qRaw` legs on `text_subjects.subject` |
| `idx_text_genres_genre_trgm` | `text_genres` | `LOWER(genre) gin_trgm_ops` | GIN trgm | substring `LIKE '%q%'` and fuzzy `% :qRaw` legs on `text_genres.genre` |
| `idx_text_tags_tag_trgm` | `text_tags` | `LOWER(tag) gin_trgm_ops` | GIN trgm | substring `LIKE '%q%'` and fuzzy `% :qRaw` legs on `text_tags.tag` |
| `idx_text_keywords_keyword_trgm` | `text_keywords` | `LOWER(keyword) gin_trgm_ops` | GIN trgm | substring `LIKE '%q%'` and fuzzy `% :qRaw` legs on `text_keywords.keyword` |
| `idx_text_subjects_subject_pat` | `text_subjects` | `LOWER(subject) text_pattern_ops` | btree | prefix `LIKE 'q%'` leg on `text_subjects.subject` (works at 1–2 chars) |
| `idx_text_genres_genre_pat` | `text_genres` | `LOWER(genre) text_pattern_ops` | btree | prefix `LIKE 'q%'` leg on `text_genres.genre` (works at 1–2 chars) |
| `idx_text_tags_tag_pat` | `text_tags` | `LOWER(tag) text_pattern_ops` | btree | prefix `LIKE 'q%'` leg on `text_tags.tag` (works at 1–2 chars) |
| `idx_text_keywords_keyword_pat` | `text_keywords` | `LOWER(keyword) text_pattern_ops` | btree | prefix `LIKE 'q%'` leg on `text_keywords.keyword` (works at 1–2 chars) |
| `idx_text_subjects_text_id` | `text_subjects` | `text_id` | btree | child-table join / phase-2 `similarity()` subquery keyed on `text_subjects.text_id` |
| `idx_text_genres_text_id` | `text_genres` | `text_id` | btree | child-table join / phase-2 `similarity()` subquery keyed on `text_genres.text_id` |
| `idx_text_tags_text_id` | `text_tags` | `text_id` | btree | child-table join / phase-2 `similarity()` subquery keyed on `text_tags.text_id` |
| `idx_text_keywords_text_id` | `text_keywords` | `text_id` | btree | child-table join / phase-2 `similarity()` subquery keyed on `text_keywords.text_id` |

#### Video — 48 indexes (28 GIN trgm, 20 btree)

Serves `VideoRepository.searchByText` (guest) / `VIDEO_SEARCH_SPEC` via `MediaSearchSqlBuilder` (staff).

| Index | Table | Columns / expression | Type | Query leg it serves |
|---|---|---|---|---|
| `idx_videos_video_code_trgm` | `videos` | `LOWER(video_code) gin_trgm_ops` | GIN trgm | substring `LIKE '%q%'` and fuzzy `% :qRaw` legs on `videos.video_code` |
| `idx_videos_file_name_trgm` | `videos` | `LOWER(file_name) gin_trgm_ops` | GIN trgm | substring `LIKE '%q%'` and fuzzy `% :qRaw` legs on `videos.file_name` |
| `idx_videos_volume_name_trgm` | `videos` | `LOWER(volume_name) gin_trgm_ops` | GIN trgm | substring `LIKE '%q%'` and fuzzy `% :qRaw` legs on `videos.volume_name` |
| `idx_videos_directory_trgm` | `videos` | `LOWER(directory) gin_trgm_ops` | GIN trgm | substring `LIKE '%q%'` and fuzzy `% :qRaw` legs on `videos.directory` |
| `idx_videos_path_external_trgm` | `videos` | `LOWER(path_in_external_volume) gin_trgm_ops` | GIN trgm | substring `LIKE '%q%'` and fuzzy `% :qRaw` legs on `videos.path_in_external_volume` |
| `idx_videos_auto_path_trgm` | `videos` | `LOWER(auto_path) gin_trgm_ops` | GIN trgm | substring `LIKE '%q%'` and fuzzy `% :qRaw` legs on `videos.auto_path` |
| `idx_videos_original_title_trgm` | `videos` | `LOWER(original_title) gin_trgm_ops` | GIN trgm | substring `LIKE '%q%'` and fuzzy `% :qRaw` legs on `videos.original_title` |
| `idx_videos_alternative_title_trgm` | `videos` | `LOWER(alternative_title) gin_trgm_ops` | GIN trgm | substring `LIKE '%q%'` and fuzzy `% :qRaw` legs on `videos.alternative_title` |
| `idx_videos_central_kurdish_title_trgm` | `videos` | `LOWER(title_in_central_kurdish) gin_trgm_ops` | GIN trgm | substring `LIKE '%q%'` and fuzzy `% :qRaw` legs on `videos.title_in_central_kurdish` |
| `idx_videos_romanized_title_trgm` | `videos` | `LOWER(romanized_title) gin_trgm_ops` | GIN trgm | substring `LIKE '%q%'` and fuzzy `% :qRaw` legs on `videos.romanized_title` |
| `idx_videos_event_trgm` | `videos` | `LOWER(event) gin_trgm_ops` | GIN trgm | substring `LIKE '%q%'` and fuzzy `% :qRaw` legs on `videos.event` |
| `idx_videos_location_trgm` | `videos` | `LOWER(location) gin_trgm_ops` | GIN trgm | substring `LIKE '%q%'` and fuzzy `% :qRaw` legs on `videos.location` |
| `idx_videos_description_trgm` | `videos` | `LOWER(description) gin_trgm_ops` | GIN trgm | substring `LIKE '%q%'` and fuzzy `% :qRaw` legs on `videos.description` |
| `idx_videos_person_shown_trgm` | `videos` | `LOWER(person_shown_in_video) gin_trgm_ops` | GIN trgm | substring `LIKE '%q%'` and fuzzy `% :qRaw` legs on `videos.person_shown_in_video` |
| `idx_videos_resolution_trgm` | `videos` | `LOWER(resolution) gin_trgm_ops` | GIN trgm | substring `LIKE '%q%'` and fuzzy `% :qRaw` legs on `videos.resolution` |
| `idx_videos_codec_trgm` | `videos` | `LOWER(video_codec) gin_trgm_ops` | GIN trgm | substring `LIKE '%q%'` and fuzzy `% :qRaw` legs on `videos.video_codec` |
| `idx_videos_subtitle_trgm` | `videos` | `LOWER(subtitle) gin_trgm_ops` | GIN trgm | substring `LIKE '%q%'` and fuzzy `% :qRaw` legs on `videos.subtitle` |
| `idx_videos_creator_trgm` | `videos` | `LOWER(creator_artist_director) gin_trgm_ops` | GIN trgm | substring `LIKE '%q%'` and fuzzy `% :qRaw` legs on `videos.creator_artist_director` |
| `idx_videos_producer_trgm` | `videos` | `LOWER(producer) gin_trgm_ops` | GIN trgm | substring `LIKE '%q%'` and fuzzy `% :qRaw` legs on `videos.producer` |
| `idx_videos_contributor_trgm` | `videos` | `LOWER(contributor) gin_trgm_ops` | GIN trgm | substring `LIKE '%q%'` and fuzzy `% :qRaw` legs on `videos.contributor` |
| `idx_videos_provenance_trgm` | `videos` | `LOWER(provenance) gin_trgm_ops` | GIN trgm | substring `LIKE '%q%'` and fuzzy `% :qRaw` legs on `videos.provenance` |
| `idx_videos_note_trgm` | `videos` | `LOWER(note) gin_trgm_ops` | GIN trgm | substring `LIKE '%q%'` and fuzzy `% :qRaw` legs on `videos.note` |
| `idx_videos_video_code_pat` | `videos` | `LOWER(video_code) text_pattern_ops` | btree | prefix `LIKE 'q%'` leg on `videos.video_code` (works at 1–2 chars) |
| `idx_videos_file_name_pat` | `videos` | `LOWER(file_name) text_pattern_ops` | btree | prefix `LIKE 'q%'` leg on `videos.file_name` (works at 1–2 chars) |
| `idx_videos_original_title_pat` | `videos` | `LOWER(original_title) text_pattern_ops` | btree | prefix `LIKE 'q%'` leg on `videos.original_title` (works at 1–2 chars) |
| `idx_videos_alternative_title_pat` | `videos` | `LOWER(alternative_title) text_pattern_ops` | btree | prefix `LIKE 'q%'` leg on `videos.alternative_title` (works at 1–2 chars) |
| `idx_videos_central_kurdish_title_pat` | `videos` | `LOWER(title_in_central_kurdish) text_pattern_ops` | btree | prefix `LIKE 'q%'` leg on `videos.title_in_central_kurdish` (works at 1–2 chars) |
| `idx_videos_romanized_title_pat` | `videos` | `LOWER(romanized_title) text_pattern_ops` | btree | prefix `LIKE 'q%'` leg on `videos.romanized_title` (works at 1–2 chars) |
| `idx_videos_creator_pat` | `videos` | `LOWER(creator_artist_director) text_pattern_ops` | btree | prefix `LIKE 'q%'` leg on `videos.creator_artist_director` (works at 1–2 chars) |
| `idx_videos_producer_pat` | `videos` | `LOWER(producer) text_pattern_ops` | btree | prefix `LIKE 'q%'` leg on `videos.producer` (works at 1–2 chars) |
| `idx_videos_event_pat` | `videos` | `LOWER(event) text_pattern_ops` | btree | prefix `LIKE 'q%'` leg on `videos.event` (works at 1–2 chars) |
| `idx_videos_person_shown_pat` | `videos` | `LOWER(person_shown_in_video) text_pattern_ops` | btree | prefix `LIKE 'q%'` leg on `videos.person_shown_in_video` (works at 1–2 chars) |
| `idx_video_subjects_subject_trgm` | `video_subjects` | `LOWER(subject) gin_trgm_ops` | GIN trgm | substring `LIKE '%q%'` and fuzzy `% :qRaw` legs on `video_subjects.subject` |
| `idx_video_genres_genre_trgm` | `video_genres` | `LOWER(genre) gin_trgm_ops` | GIN trgm | substring `LIKE '%q%'` and fuzzy `% :qRaw` legs on `video_genres.genre` |
| `idx_video_colors_color_trgm` | `video_colors` | `LOWER(color) gin_trgm_ops` | GIN trgm | substring `LIKE '%q%'` and fuzzy `% :qRaw` legs on `video_colors.color` |
| `idx_video_usages_usage_trgm` | `video_usages` | `LOWER(usage_context) gin_trgm_ops` | GIN trgm | substring `LIKE '%q%'` and fuzzy `% :qRaw` legs on `video_usages.usage_context` |
| `idx_video_tags_tag_trgm` | `video_tags` | `LOWER(tag) gin_trgm_ops` | GIN trgm | substring `LIKE '%q%'` and fuzzy `% :qRaw` legs on `video_tags.tag` |
| `idx_video_keywords_keyword_trgm` | `video_keywords` | `LOWER(keyword) gin_trgm_ops` | GIN trgm | substring `LIKE '%q%'` and fuzzy `% :qRaw` legs on `video_keywords.keyword` |
| `idx_video_subjects_subject_pat` | `video_subjects` | `LOWER(subject) text_pattern_ops` | btree | prefix `LIKE 'q%'` leg on `video_subjects.subject` (works at 1–2 chars) |
| `idx_video_genres_genre_pat` | `video_genres` | `LOWER(genre) text_pattern_ops` | btree | prefix `LIKE 'q%'` leg on `video_genres.genre` (works at 1–2 chars) |
| `idx_video_tags_tag_pat` | `video_tags` | `LOWER(tag) text_pattern_ops` | btree | prefix `LIKE 'q%'` leg on `video_tags.tag` (works at 1–2 chars) |
| `idx_video_keywords_keyword_pat` | `video_keywords` | `LOWER(keyword) text_pattern_ops` | btree | prefix `LIKE 'q%'` leg on `video_keywords.keyword` (works at 1–2 chars) |
| `idx_video_subjects_video_id` | `video_subjects` | `video_id` | btree | child-table join / phase-2 `similarity()` subquery keyed on `video_subjects.video_id` |
| `idx_video_genres_video_id` | `video_genres` | `video_id` | btree | child-table join / phase-2 `similarity()` subquery keyed on `video_genres.video_id` |
| `idx_video_colors_video_id` | `video_colors` | `video_id` | btree | child-table join / phase-2 `similarity()` subquery keyed on `video_colors.video_id` |
| `idx_video_usages_video_id` | `video_usages` | `video_id` | btree | child-table join / phase-2 `similarity()` subquery keyed on `video_usages.video_id` |
| `idx_video_tags_video_id` | `video_tags` | `video_id` | btree | child-table join / phase-2 `similarity()` subquery keyed on `video_tags.video_id` |
| `idx_video_keywords_video_id` | `video_keywords` | `video_id` | btree | child-table join / phase-2 `similarity()` subquery keyed on `video_keywords.video_id` |

#### Audio — 53 indexes (31 GIN trgm, 22 btree)

Serves `AudioRepository.searchByText` (guest) / `AUDIO_SEARCH_SPEC` via `MediaSearchSqlBuilder` (staff).

| Index | Table | Columns / expression | Type | Query leg it serves |
|---|---|---|---|---|
| `idx_audios_audio_code_trgm` | `audios` | `LOWER(audio_code) gin_trgm_ops` | GIN trgm | substring `LIKE '%q%'` and fuzzy `% :qRaw` legs on `audios.audio_code` |
| `idx_audios_file_name_trgm` | `audios` | `LOWER(file_name) gin_trgm_ops` | GIN trgm | substring `LIKE '%q%'` and fuzzy `% :qRaw` legs on `audios.file_name` |
| `idx_audios_volume_name_trgm` | `audios` | `LOWER(volume_name) gin_trgm_ops` | GIN trgm | substring `LIKE '%q%'` and fuzzy `% :qRaw` legs on `audios.volume_name` |
| `idx_audios_directory_name_trgm` | `audios` | `LOWER(directory_name) gin_trgm_ops` | GIN trgm | substring `LIKE '%q%'` and fuzzy `% :qRaw` legs on `audios.directory_name` |
| `idx_audios_path_external_trgm` | `audios` | `LOWER(path_in_external) gin_trgm_ops` | GIN trgm | substring `LIKE '%q%'` and fuzzy `% :qRaw` legs on `audios.path_in_external` |
| `idx_audios_auto_path_trgm` | `audios` | `LOWER(auto_path) gin_trgm_ops` | GIN trgm | substring `LIKE '%q%'` and fuzzy `% :qRaw` legs on `audios.auto_path` |
| `idx_audios_origin_title_trgm` | `audios` | `LOWER(origin_title) gin_trgm_ops` | GIN trgm | substring `LIKE '%q%'` and fuzzy `% :qRaw` legs on `audios.origin_title` |
| `idx_audios_alter_title_trgm` | `audios` | `LOWER(alter_title) gin_trgm_ops` | GIN trgm | substring `LIKE '%q%'` and fuzzy `% :qRaw` legs on `audios.alter_title` |
| `idx_audios_central_kurdish_title_trgm` | `audios` | `LOWER(central_kurdish_title) gin_trgm_ops` | GIN trgm | substring `LIKE '%q%'` and fuzzy `% :qRaw` legs on `audios.central_kurdish_title` |
| `idx_audios_romanized_title_trgm` | `audios` | `LOWER(romanized_title) gin_trgm_ops` | GIN trgm | substring `LIKE '%q%'` and fuzzy `% :qRaw` legs on `audios.romanized_title` |
| `idx_audios_form_trgm` | `audios` | `LOWER(form) gin_trgm_ops` | GIN trgm | substring `LIKE '%q%'` and fuzzy `% :qRaw` legs on `audios.form` |
| `idx_audios_type_of_basta_trgm` | `audios` | `LOWER(type_of_basta) gin_trgm_ops` | GIN trgm | substring `LIKE '%q%'` and fuzzy `% :qRaw` legs on `audios.type_of_basta` |
| `idx_audios_type_of_maqam_trgm` | `audios` | `LOWER(type_of_maqam) gin_trgm_ops` | GIN trgm | substring `LIKE '%q%'` and fuzzy `% :qRaw` legs on `audios.type_of_maqam` |
| `idx_audios_abstract_trgm` | `audios` | `LOWER(abstract_text) gin_trgm_ops` | GIN trgm | substring `LIKE '%q%'` and fuzzy `% :qRaw` legs on `audios.abstract_text` |
| `idx_audios_description_trgm` | `audios` | `LOWER(description) gin_trgm_ops` | GIN trgm | substring `LIKE '%q%'` and fuzzy `% :qRaw` legs on `audios.description` |
| `idx_audios_speaker_trgm` | `audios` | `LOWER(speaker) gin_trgm_ops` | GIN trgm | substring `LIKE '%q%'` and fuzzy `% :qRaw` legs on `audios.speaker` |
| `idx_audios_producer_trgm` | `audios` | `LOWER(producer) gin_trgm_ops` | GIN trgm | substring `LIKE '%q%'` and fuzzy `% :qRaw` legs on `audios.producer` |
| `idx_audios_composer_trgm` | `audios` | `LOWER(composer) gin_trgm_ops` | GIN trgm | substring `LIKE '%q%'` and fuzzy `% :qRaw` legs on `audios.composer` |
| `idx_audios_language_trgm` | `audios` | `LOWER(language) gin_trgm_ops` | GIN trgm | substring `LIKE '%q%'` and fuzzy `% :qRaw` legs on `audios.language` |
| `idx_audios_dialect_trgm` | `audios` | `LOWER(dialect) gin_trgm_ops` | GIN trgm | substring `LIKE '%q%'` and fuzzy `% :qRaw` legs on `audios.dialect` |
| `idx_audios_lyrics_trgm` | `audios` | `LOWER(lyrics) gin_trgm_ops` | GIN trgm | substring `LIKE '%q%'` and fuzzy `% :qRaw` legs on `audios.lyrics` |
| `idx_audios_poet_trgm` | `audios` | `LOWER(poet) gin_trgm_ops` | GIN trgm | substring `LIKE '%q%'` and fuzzy `% :qRaw` legs on `audios.poet` |
| `idx_audios_recording_venue_trgm` | `audios` | `LOWER(recording_venue) gin_trgm_ops` | GIN trgm | substring `LIKE '%q%'` and fuzzy `% :qRaw` legs on `audios.recording_venue` |
| `idx_audios_city_trgm` | `audios` | `LOWER(city) gin_trgm_ops` | GIN trgm | substring `LIKE '%q%'` and fuzzy `% :qRaw` legs on `audios.city` |
| `idx_audios_region_trgm` | `audios` | `LOWER(region) gin_trgm_ops` | GIN trgm | substring `LIKE '%q%'` and fuzzy `% :qRaw` legs on `audios.region` |
| `idx_audios_provenance_trgm` | `audios` | `LOWER(provenance) gin_trgm_ops` | GIN trgm | substring `LIKE '%q%'` and fuzzy `% :qRaw` legs on `audios.provenance` |
| `idx_audios_audio_file_note_trgm` | `audios` | `LOWER(audio_file_note) gin_trgm_ops` | GIN trgm | substring `LIKE '%q%'` and fuzzy `% :qRaw` legs on `audios.audio_file_note` |
| `idx_audios_audio_code_pat` | `audios` | `LOWER(audio_code) text_pattern_ops` | btree | prefix `LIKE 'q%'` leg on `audios.audio_code` (works at 1–2 chars) |
| `idx_audios_file_name_pat` | `audios` | `LOWER(file_name) text_pattern_ops` | btree | prefix `LIKE 'q%'` leg on `audios.file_name` (works at 1–2 chars) |
| `idx_audios_origin_title_pat` | `audios` | `LOWER(origin_title) text_pattern_ops` | btree | prefix `LIKE 'q%'` leg on `audios.origin_title` (works at 1–2 chars) |
| `idx_audios_alter_title_pat` | `audios` | `LOWER(alter_title) text_pattern_ops` | btree | prefix `LIKE 'q%'` leg on `audios.alter_title` (works at 1–2 chars) |
| `idx_audios_central_kurdish_title_pat` | `audios` | `LOWER(central_kurdish_title) text_pattern_ops` | btree | prefix `LIKE 'q%'` leg on `audios.central_kurdish_title` (works at 1–2 chars) |
| `idx_audios_romanized_title_pat` | `audios` | `LOWER(romanized_title) text_pattern_ops` | btree | prefix `LIKE 'q%'` leg on `audios.romanized_title` (works at 1–2 chars) |
| `idx_audios_speaker_pat` | `audios` | `LOWER(speaker) text_pattern_ops` | btree | prefix `LIKE 'q%'` leg on `audios.speaker` (works at 1–2 chars) |
| `idx_audios_composer_pat` | `audios` | `LOWER(composer) text_pattern_ops` | btree | prefix `LIKE 'q%'` leg on `audios.composer` (works at 1–2 chars) |
| `idx_audios_poet_pat` | `audios` | `LOWER(poet) text_pattern_ops` | btree | prefix `LIKE 'q%'` leg on `audios.poet` (works at 1–2 chars) |
| `idx_audios_producer_pat` | `audios` | `LOWER(producer) text_pattern_ops` | btree | prefix `LIKE 'q%'` leg on `audios.producer` (works at 1–2 chars) |
| `idx_audios_city_pat` | `audios` | `LOWER(city) text_pattern_ops` | btree | prefix `LIKE 'q%'` leg on `audios.city` (works at 1–2 chars) |
| `idx_audios_region_pat` | `audios` | `LOWER(region) text_pattern_ops` | btree | prefix `LIKE 'q%'` leg on `audios.region` (works at 1–2 chars) |
| `idx_audios_type_of_basta_pat` | `audios` | `LOWER(type_of_basta) text_pattern_ops` | btree | prefix `LIKE 'q%'` leg on `audios.type_of_basta` (works at 1–2 chars) |
| `idx_audios_type_of_maqam_pat` | `audios` | `LOWER(type_of_maqam) text_pattern_ops` | btree | prefix `LIKE 'q%'` leg on `audios.type_of_maqam` (works at 1–2 chars) |
| `idx_audio_genres_genre_trgm` | `audio_genres` | `LOWER(genre) gin_trgm_ops` | GIN trgm | substring `LIKE '%q%'` and fuzzy `% :qRaw` legs on `audio_genres.genre` |
| `idx_audio_contributors_contributor_trgm` | `audio_contributors` | `LOWER(contributor) gin_trgm_ops` | GIN trgm | substring `LIKE '%q%'` and fuzzy `% :qRaw` legs on `audio_contributors.contributor` |
| `idx_audio_tags_tag_trgm` | `audio_tags` | `LOWER(tag) gin_trgm_ops` | GIN trgm | substring `LIKE '%q%'` and fuzzy `% :qRaw` legs on `audio_tags.tag` |
| `idx_audio_keywords_keyword_trgm` | `audio_keywords` | `LOWER(keyword) gin_trgm_ops` | GIN trgm | substring `LIKE '%q%'` and fuzzy `% :qRaw` legs on `audio_keywords.keyword` |
| `idx_audio_genres_genre_pat` | `audio_genres` | `LOWER(genre) text_pattern_ops` | btree | prefix `LIKE 'q%'` leg on `audio_genres.genre` (works at 1–2 chars) |
| `idx_audio_contributors_contributor_pat` | `audio_contributors` | `LOWER(contributor) text_pattern_ops` | btree | prefix `LIKE 'q%'` leg on `audio_contributors.contributor` (works at 1–2 chars) |
| `idx_audio_tags_tag_pat` | `audio_tags` | `LOWER(tag) text_pattern_ops` | btree | prefix `LIKE 'q%'` leg on `audio_tags.tag` (works at 1–2 chars) |
| `idx_audio_keywords_keyword_pat` | `audio_keywords` | `LOWER(keyword) text_pattern_ops` | btree | prefix `LIKE 'q%'` leg on `audio_keywords.keyword` (works at 1–2 chars) |
| `idx_audio_genres_audio_id` | `audio_genres` | `audio_id` | btree | child-table join / phase-2 `similarity()` subquery keyed on `audio_genres.audio_id` |
| `idx_audio_contributors_audio_id` | `audio_contributors` | `audio_id` | btree | child-table join / phase-2 `similarity()` subquery keyed on `audio_contributors.audio_id` |
| `idx_audio_tags_audio_id` | `audio_tags` | `audio_id` | btree | child-table join / phase-2 `similarity()` subquery keyed on `audio_tags.audio_id` |
| `idx_audio_keywords_audio_id` | `audio_keywords` | `audio_id` | btree | child-table join / phase-2 `similarity()` subquery keyed on `audio_keywords.audio_id` |

#### Media-index coverage gaps

| Gap | Detail |
|---|---|
| `audio_subjects` | The table is a `UNION` leg **and** a tier-3 `similarity()` subquery in `AudioRepository.searchByText` (the guest audio search), but `ensureAudioIndexes()` creates no `idx_audio_subjects_*` trigram index and no `idx_audio_subjects_audio_id` FK btree. The other four audio child tables get all three. |
| `text_colors` / `text_usages` | Do not exist — `Text` declares only `text_subjects`, `text_genres`, `text_tags`, `text_keywords`. No index is missing; the shorter list is correct. |
| No `_pat` btree on the colour / usage child tables | `image_colors`, `image_usages`, `video_colors` and `video_usages` get a `_trgm` GIN index and an FK btree, but no `LOWER(col) text_pattern_ops` btree — `createBtreePatternIndex` is called only for the subject / genre / tag / keyword child tables. Their `UNION` leg still tests `LIKE LOWER(:q) \|\| '%'`, so the 1–2-character prefix probe on a colour or usage value is unindexed. |
| Columns searched but not indexed | Each `searchByText` query tests more columns than the initializer indexes (e.g. `images.manufacturer`, `images.model`, `images.lens`, `images.copyright`). Those legs are unindexed by design — they are cheap OR branches evaluated on rows the indexed legs already qualified. |

### 2. Category search indexes — `CategorySearchIndexInitializer`

Three GIN indexes. All statements, verbatim:

```java
jdbcTemplate.execute("CREATE EXTENSION IF NOT EXISTS pg_trgm");
jdbcTemplate.execute(
        "CREATE INDEX IF NOT EXISTS idx_categories_name_lower_trgm " +
                "ON categories USING GIN (LOWER(name) gin_trgm_ops)"
);
jdbcTemplate.execute(
        "CREATE INDEX IF NOT EXISTS idx_categories_description_lower_trgm " +
                "ON categories USING GIN (LOWER(description) gin_trgm_ops)"
);
jdbcTemplate.execute(
        "CREATE INDEX IF NOT EXISTS idx_category_keywords_lower_trgm " +
                "ON category_keywords USING GIN (LOWER(keyword) gin_trgm_ops)"
);
```

| Index | Table | Columns / expression | Type | Query it serves |
|---|---|---|---|---|
| `idx_categories_name_lower_trgm` | `categories` | `LOWER(name) gin_trgm_ops` | GIN trgm | `CategoryRepository.searchByText` — the `LOWER(c.name) LIKE …` leg, the `similarity(LOWER(c.name), LOWER(:q)) > :threshold` leg, and the `GREATEST(...)` ranking term |
| `idx_categories_description_lower_trgm` | `categories` | `LOWER(description) gin_trgm_ops` | GIN trgm | `CategoryRepository.searchByText` — the `LOWER(COALESCE(c.description, '')) LIKE …` leg |
| `idx_category_keywords_lower_trgm` | `category_keywords` | `LOWER(keyword) gin_trgm_ops` | GIN trgm | The `EXISTS (SELECT 1 FROM category_keywords k …)` substring and similarity subqueries in `CategoryRepository.searchByText`; also the `category_keywords` leg of `KeywordSuggestRepository.suggest` |

`categories.category_code` is searched (`LOWER(c.category_code) LIKE …`) but has no trigram index —
only btrees on the raw column value (`idx_category_code` from the entity declaration, plus the
unique index behind `@Column(unique = true)`), and neither serves a leading-wildcard pattern over a
`LOWER(...)` expression.

### 3. Person search indexes — `PersonSearchIndexInitializer`

Ten GIN indexes. All statements, verbatim:

```java
jdbcTemplate.execute("CREATE EXTENSION IF NOT EXISTS pg_trgm");

// Primary name fields — used for similarity ranking, so must be indexed.
"CREATE INDEX IF NOT EXISTS idx_person_full_name_lower_trgm       ON person USING GIN (LOWER(full_name) gin_trgm_ops)"
"CREATE INDEX IF NOT EXISTS idx_person_nickname_lower_trgm        ON person USING GIN (LOWER(nickname) gin_trgm_ops)"
"CREATE INDEX IF NOT EXISTS idx_person_romanized_name_lower_trgm  ON person USING GIN (LOWER(romanized_name) gin_trgm_ops)"

// Secondary substring fields — index speeds up the LIKE legs of the query.
"CREATE INDEX IF NOT EXISTS idx_person_description_lower_trgm     ON person USING GIN (LOWER(description) gin_trgm_ops)"
"CREATE INDEX IF NOT EXISTS idx_person_tag_lower_trgm             ON person USING GIN (LOWER(tag) gin_trgm_ops)"
"CREATE INDEX IF NOT EXISTS idx_person_keywords_lower_trgm        ON person USING GIN (LOWER(keywords) gin_trgm_ops)"
"CREATE INDEX IF NOT EXISTS idx_person_region_lower_trgm          ON person USING GIN (LOWER(region) gin_trgm_ops)"
"CREATE INDEX IF NOT EXISTS idx_person_place_of_birth_lower_trgm  ON person USING GIN (LOWER(place_of_birth) gin_trgm_ops)"
"CREATE INDEX IF NOT EXISTS idx_person_place_of_death_lower_trgm  ON person USING GIN (LOWER(place_of_death) gin_trgm_ops)"

// person_type ElementCollection lives in a side table.
"CREATE INDEX IF NOT EXISTS idx_person_person_type_lower_trgm     ON person_person_type USING GIN (LOWER(person_type) gin_trgm_ops)"
```

| Index | Table | Columns / expression | Type | Query it serves |
|---|---|---|---|---|
| `idx_person_full_name_lower_trgm` | `person` | `LOWER(full_name) gin_trgm_ops` | GIN trgm | `PersonRepository.searchByText` — `LIKE`, `similarity(...) > :threshold`, and the `GREATEST(...)` ranking term |
| `idx_person_nickname_lower_trgm` | `person` | `LOWER(nickname) gin_trgm_ops` | GIN trgm | Same three legs on `nickname` |
| `idx_person_romanized_name_lower_trgm` | `person` | `LOWER(romanized_name) gin_trgm_ops` | GIN trgm | Same three legs on `romanized_name` |
| `idx_person_description_lower_trgm` | `person` | `LOWER(description) gin_trgm_ops` | GIN trgm | Substring `LIKE` leg only |
| `idx_person_tag_lower_trgm` | `person` | `LOWER(tag) gin_trgm_ops` | GIN trgm | Substring `LIKE` leg only |
| `idx_person_keywords_lower_trgm` | `person` | `LOWER(keywords) gin_trgm_ops` | GIN trgm | Substring `LIKE` leg only |
| `idx_person_region_lower_trgm` | `person` | `LOWER(region) gin_trgm_ops` | GIN trgm | Substring `LIKE` leg only (a second, plain btree `idx_person_region` is declared on the entity for equality filters) |
| `idx_person_place_of_birth_lower_trgm` | `person` | `LOWER(place_of_birth) gin_trgm_ops` | GIN trgm | Substring `LIKE` leg only |
| `idx_person_place_of_death_lower_trgm` | `person` | `LOWER(place_of_death) gin_trgm_ops` | GIN trgm | Substring `LIKE` leg only |
| `idx_person_person_type_lower_trgm` | `person_person_type` | `LOWER(person_type) gin_trgm_ops` | GIN trgm | The `EXISTS (SELECT 1 FROM person_person_type pt …)` subquery |

`person.person_code` is searched with `LOWER(p.person_code) LIKE …` but carries only btrees on the
raw column value — `idx_person_code` from the entity declaration plus the unique index behind
`@Column(unique = true)`. No trigram index exists for it.

### 4. Audit-log analytics indexes — `AuditLogIndexInitializer`

Three indexes per table, generated in a loop over an eleven-entry list:

```java
for (String table : TABLES) {
    createIndex(
            "idx_" + table + "_actor_occurred",
            "ON " + table + " (actor_username, occurred_at DESC)");
    createIndex(
            "idx_" + table + "_occurred",
            "ON " + table + " (occurred_at DESC)");
    createIndex(
            "idx_" + table + "_action_occurred",
            "ON " + table + " (action, occurred_at DESC)");
}
```

These serve `AnalyticsService`, which pushes a single `UNION ALL` CTE named `all_logs` across the
audit tables into Postgres rather than issuing one query per table. Every branch of that CTE selects
`action::text`, `actor_username` and `occurred_at`, which is precisely the column set indexed here.

| Index | Table | Columns / expression | Type | Query it serves |
|---|---|---|---|---|
| `idx_audio_audit_logs_actor_occurred` | `audio_audit_logs` | `(actor_username, occurred_at DESC)` | btree | per-user windowed scan — `/api/analytics/me`, `/api/analytics/users/{username}` |
| `idx_audio_audit_logs_occurred` | `audio_audit_logs` | `(occurred_at DESC)` | btree | team-wide windowed scan — `/api/analytics/overview`, `/api/analytics/users` |
| `idx_audio_audit_logs_action_occurred` | `audio_audit_logs` | `(action, occurred_at DESC)` | btree | `FILTER (WHERE action = …)` aggregations and the `actions=` filter |
| `idx_video_audit_logs_actor_occurred` | `video_audit_logs` | `(actor_username, occurred_at DESC)` | btree | per-user windowed scan — `/api/analytics/me`, `/api/analytics/users/{username}` |
| `idx_video_audit_logs_occurred` | `video_audit_logs` | `(occurred_at DESC)` | btree | team-wide windowed scan — `/api/analytics/overview`, `/api/analytics/users` |
| `idx_video_audit_logs_action_occurred` | `video_audit_logs` | `(action, occurred_at DESC)` | btree | `FILTER (WHERE action = …)` aggregations and the `actions=` filter |
| `idx_image_audit_logs_actor_occurred` | `image_audit_logs` | `(actor_username, occurred_at DESC)` | btree | per-user windowed scan — `/api/analytics/me`, `/api/analytics/users/{username}` |
| `idx_image_audit_logs_occurred` | `image_audit_logs` | `(occurred_at DESC)` | btree | team-wide windowed scan — `/api/analytics/overview`, `/api/analytics/users` |
| `idx_image_audit_logs_action_occurred` | `image_audit_logs` | `(action, occurred_at DESC)` | btree | `FILTER (WHERE action = …)` aggregations and the `actions=` filter |
| `idx_text_audit_logs_actor_occurred` | `text_audit_logs` | `(actor_username, occurred_at DESC)` | btree | per-user windowed scan — `/api/analytics/me`, `/api/analytics/users/{username}` |
| `idx_text_audit_logs_occurred` | `text_audit_logs` | `(occurred_at DESC)` | btree | team-wide windowed scan — `/api/analytics/overview`, `/api/analytics/users` |
| `idx_text_audit_logs_action_occurred` | `text_audit_logs` | `(action, occurred_at DESC)` | btree | `FILTER (WHERE action = …)` aggregations and the `actions=` filter |
| `idx_project_audit_logs_actor_occurred` | `project_audit_logs` | `(actor_username, occurred_at DESC)` | btree | per-user windowed scan — `/api/analytics/me`, `/api/analytics/users/{username}` |
| `idx_project_audit_logs_occurred` | `project_audit_logs` | `(occurred_at DESC)` | btree | team-wide windowed scan — `/api/analytics/overview`, `/api/analytics/users` |
| `idx_project_audit_logs_action_occurred` | `project_audit_logs` | `(action, occurred_at DESC)` | btree | `FILTER (WHERE action = …)` aggregations and the `actions=` filter |
| `idx_category_audit_logs_actor_occurred` | `category_audit_logs` | `(actor_username, occurred_at DESC)` | btree | per-user windowed scan — `/api/analytics/me`, `/api/analytics/users/{username}` |
| `idx_category_audit_logs_occurred` | `category_audit_logs` | `(occurred_at DESC)` | btree | team-wide windowed scan — `/api/analytics/overview`, `/api/analytics/users` |
| `idx_category_audit_logs_action_occurred` | `category_audit_logs` | `(action, occurred_at DESC)` | btree | `FILTER (WHERE action = …)` aggregations and the `actions=` filter |
| `idx_person_audit_logs_actor_occurred` | `person_audit_logs` | `(actor_username, occurred_at DESC)` | btree | per-user windowed scan — `/api/analytics/me`, `/api/analytics/users/{username}` |
| `idx_person_audit_logs_occurred` | `person_audit_logs` | `(occurred_at DESC)` | btree | team-wide windowed scan — `/api/analytics/overview`, `/api/analytics/users` |
| `idx_person_audit_logs_action_occurred` | `person_audit_logs` | `(action, occurred_at DESC)` | btree | `FILTER (WHERE action = …)` aggregations and the `actions=` filter |
| `idx_maqam_audit_logs_actor_occurred` | `maqam_audit_logs` | `(actor_username, occurred_at DESC)` | btree | per-user windowed scan — `/api/analytics/me`, `/api/analytics/users/{username}` |
| `idx_maqam_audit_logs_occurred` | `maqam_audit_logs` | `(occurred_at DESC)` | btree | team-wide windowed scan — `/api/analytics/overview`, `/api/analytics/users` |
| `idx_maqam_audit_logs_action_occurred` | `maqam_audit_logs` | `(action, occurred_at DESC)` | btree | `FILTER (WHERE action = …)` aggregations and the `actions=` filter |
| `idx_physical_media_audit_logs_actor_occurred` | `physical_media_audit_logs` | `(actor_username, occurred_at DESC)` | btree | per-user windowed scan — `/api/analytics/me`, `/api/analytics/users/{username}` |
| `idx_physical_media_audit_logs_occurred` | `physical_media_audit_logs` | `(occurred_at DESC)` | btree | team-wide windowed scan — `/api/analytics/overview`, `/api/analytics/users` |
| `idx_physical_media_audit_logs_action_occurred` | `physical_media_audit_logs` | `(action, occurred_at DESC)` | btree | `FILTER (WHERE action = …)` aggregations and the `actions=` filter |
| `idx_analytics_audit_logs_actor_occurred` | `analytics_audit_logs` | `(actor_username, occurred_at DESC)` | btree | per-user windowed scan — `/api/analytics/me`, `/api/analytics/users/{username}` |
| `idx_analytics_audit_logs_occurred` | `analytics_audit_logs` | `(occurred_at DESC)` | btree | team-wide windowed scan — `/api/analytics/overview`, `/api/analytics/users` |
| `idx_analytics_audit_logs_action_occurred` | `analytics_audit_logs` | `(action, occurred_at DESC)` | btree | `FILTER (WHERE action = …)` aggregations and the `actions=` filter |
| `idx_user_audit_logs_actor_occurred` | `user_audit_logs` | `(actor_username, occurred_at DESC)` | btree | per-user windowed scan — `/api/analytics/me`, `/api/analytics/users/{username}` |
| `idx_user_audit_logs_occurred` | `user_audit_logs` | `(occurred_at DESC)` | btree | team-wide windowed scan — `/api/analytics/overview`, `/api/analytics/users` |
| `idx_user_audit_logs_action_occurred` | `user_audit_logs` | `(action, occurred_at DESC)` | btree | `FILTER (WHERE action = …)` aggregations and the `actions=` filter |

**Mismatch worth knowing:** the initializer indexes **eleven** tables, but the `all_logs` CTE in
`AnalyticsService` unions **ten** — `analytics_audit_logs` is indexed here yet is not a branch of
the CTE. `ENTITY_KEYS` in `AnalyticsService` confirms the ten: `audio`, `video`, `image`, `text`,
`project`, `category`, `person`, `maqam`, `physical_media`, `user`. The three
`idx_analytics_audit_logs_*` indexes are therefore created but unused by the analytics reports.

Twelve `*_audit_logs` tables exist in the schema; the initializer covers eleven. The twelfth,
`guest_correction_audit_logs`, carries **no** initializer index and relies on its entity-declared
`idx_gcal_*` set instead. The `user_warnings` table is not an audit log, but it follows the same
pattern — its only indexes are the entity-declared `idx_user_warnings_*`. See the next section.

### 5. Entity-declared indexes — `@Table(indexes = …)`

Emitted by Hibernate under `ddl-auto=update`. All are btree; a multi-column `columnList` becomes one
composite index in the listed order.

| Index | Table | Columns | Declared on | Query it serves |
|---|---|---|---|---|
| `idx_audio_code` | `audios` | `audio_code` | `platform/model/audio/Audio.java` | `findByAudioCode…` lookups — every `/api/audio/{audioCode}` path |
| `idx_audio_project_id` | `audios` | `project_id` | `Audio.java` | `findAllByProject…`, project cascade trash/restore/visibility, per-project counts |
| `idx_audio_removed_at` | `audios` | `removed_at` | `Audio.java` | The `removed_at IS NULL` / `IS NOT NULL` predicate on every list, count and search query |
| `idx_video_code` | `videos` | `video_code` | `platform/model/video/Video.java` | Code lookups |
| `idx_video_project_id` | `videos` | `project_id` | `Video.java` | Project scoping and cascade |
| `idx_video_removed_at` | `videos` | `removed_at` | `Video.java` | Trash-state filtering |
| `idx_image_code` | `images` | `image_code` | `platform/model/image/Image.java` | Code lookups |
| `idx_image_project_id` | `images` | `project_id` | `Image.java` | Project scoping and cascade |
| `idx_image_removed_at` | `images` | `removed_at` | `Image.java` | Trash-state filtering |
| `idx_text_code` | `texts` | `text_code` | `platform/model/text/Text.java` | Code lookups |
| `idx_text_project_id` | `texts` | `project_id` | `Text.java` | Project scoping and cascade |
| `idx_text_removed_at` | `texts` | `removed_at` | `Text.java` | Trash-state filtering |
| `idx_project_code` | `projects` | `project_code` | `platform/model/project/Project.java` | `findByProjectCode…` |
| `idx_project_person_id` | `projects` | `person_id` | `Project.java` | `findAllByPerson…`, person cascade, per-person counts |
| `idx_project_removed_at` | `projects` | `removed_at` | `Project.java` | Trash-state filtering |
| `idx_category_code` | `categories` | `category_code` | `platform/model/category/Category.java` | `findByCategoryCode…` |
| `idx_category_removed_at` | `categories` | `removed_at` | `Category.java` | Trash-state filtering |
| `idx_person_code` | `person` | `person_code` | `platform/model/person/Person.java` | `findByPersonCode…` |
| `idx_person_region` | `person` | `region` | `Person.java` | Equality filtering on region (the trigram index covers substring matching) |
| `idx_person_removed_at` | `person` | `removed_at` | `Person.java` | Trash-state filtering |
| `idx_maqam_code` | `list_of_maqam` | `maqam_code` | `platform/model/maqam/ListOfMaqam.java` | `findByMaqamCode…` — every `/api/maqam/{maqamCode}` path including the stream endpoint |
| `idx_maqam_removed_at` | `list_of_maqam` | `removed_at` | `ListOfMaqam.java` | Active vs trash listings |
| `idx_maqam_created_at` | `list_of_maqam` | `created_at` | `ListOfMaqam.java` | `ORDER BY m.createdAt DESC` in `ListOfMaqamRepository.searchByText` and default list ordering |
| `idx_mtv_maqam` | `maqam_teacher_votes` | `list_of_maqam_id` | `platform/model/maqam/MaqamTeacherVote.java` | Vote panel load per record; `countByListOfMaqam` |
| `idx_mtv_teacher` | `maqam_teacher_votes` | `teacher_user_id` | `MaqamTeacherVote.java` | `teacherVoteStats()` `GROUP BY v.teacherUserId`, `findAllByTeacherUserId` — the javadoc names this index explicitly |
| `idx_mtv_voted_at` | `maqam_teacher_votes` | `voted_at` | `MaqamTeacherVote.java` | `findAllByListOfMaqamOrderByVotedAtAsc` |
| `idx_mals_maqam` | `maqam_audio_listen_sessions` | `list_of_maqam_id` | `platform/model/maqam/MaqamAudioListenSession.java` | `findAllByListOfMaqamIdOrderByStartedAtDesc`, `sumSecondsListened` |
| `idx_mals_teacher` | `maqam_audio_listen_sessions` | `teacher_user_id` | `MaqamAudioListenSession.java` | `listenStatsByTeacher()` `GROUP BY s.teacherUserId` — the javadoc names this index explicitly |
| `idx_mals_started_at` | `maqam_audio_listen_sessions` | `started_at` | `MaqamAudioListenSession.java` | The `OrderByStartedAtDesc` on all three session-listing queries |
| `idx_mals_session_key` | `maqam_audio_listen_sessions` | `session_key` | `MaqamAudioListenSession.java` | `findBySessionKeyAndTeacherUserId` — the merge lookup on every progress ping |
| `idx_mal_maqam` | `maqam_audit_logs` | `maqam_id` | `platform/model/maqam/MaqamAuditLog.java` | Per-record audit history |
| `idx_mal_action` | `maqam_audit_logs` | `action` | `MaqamAuditLog.java` | Action filtering (complements `idx_maqam_audit_logs_action_occurred`) |
| `idx_mal_actor` | `maqam_audit_logs` | `actor_username` | `MaqamAuditLog.java` | Per-actor audit history |
| `idx_mal_teacher` | `maqam_audit_logs` | `teacher_user_id` | `MaqamAuditLog.java` | Teacher-engagement views |
| `idx_mal_occurred_at` | `maqam_audit_logs` | `occurred_at` | `MaqamAuditLog.java` | Time-window scans |
| `idx_pm_code` | `physical_media` | `pm_code` | `platform/model/physicalmedia/PhysicalMedia.java` | `findByPmCode…`, `existsByPmCode` |
| `idx_pm_physical_label` | `physical_media` | `physical_label` | `PhysicalMedia.java` | The `(media type, physical label)` dedupe on `.xlsx` import |
| `idx_pm_media_type` | `physical_media` | `physical_media_type` | `PhysicalMedia.java` | `findMaxInventoryNumberByPhysicalMediaType`, type filtering, import dedupe |
| `idx_pm_media_category` | `physical_media` | `media_category` | `PhysicalMedia.java` | Category filtering on the inventory list |
| `idx_pm_digitization` | `physical_media` | `digitization` | `PhysicalMedia.java` | Digitization-status filtering |
| `idx_pm_need_to_clear` | `physical_media` | `need_to_clear` | `PhysicalMedia.java` | Needs-clearing filtering |
| `idx_pm_removed_at` | `physical_media` | `removed_at` | `PhysicalMedia.java` | Active vs trash listings |
| `idx_pmt_name` | `physical_media_types` | `name` | `platform/model/physicalmedia/PhysicalMediaType.java` | `findByName`, `existsByName`, `findAllOrderedByName` |
| `idx_pmal_pm` | `physical_media_audit_logs` | `physical_media_id` | `platform/model/physicalmedia/PhysicalMediaAuditLog.java` | Per-record audit history |
| `idx_pmal_action` | `physical_media_audit_logs` | `action` | `PhysicalMediaAuditLog.java` | Action filtering |
| `idx_pmal_actor` | `physical_media_audit_logs` | `actor_username` | `PhysicalMediaAuditLog.java` | Per-actor audit history |
| `idx_pmal_occurred_at` | `physical_media_audit_logs` | `occurred_at` | `PhysicalMediaAuditLog.java` | Time-window scans |
| `idx_gc_media` | `guest_corrections` | `media_type, media_code, removed_at` | `platform/model/correction/GuestCorrection.java` | `countByMediaTypeAndMediaCodeAndRemovedAtIsNull` — the per-record correction badge |
| `idx_gc_status` | `guest_corrections` | `status, removed_at` | `GuestCorrection.java` | `countByStatusAndRemovedAtIsNull` — the pending-corrections tile |
| `idx_gc_guest` | `guest_corrections` | `guest_user_id` | `GuestCorrection.java` | `findAllByGuestUserIdAndRemovedAtIsNull` — a guest's own submissions |
| `idx_gc_created_at` | `guest_corrections` | `created_at` | `GuestCorrection.java` | Default newest-first ordering |
| `idx_gcal_correction` | `guest_correction_audit_logs` | `correction_id` | `platform/model/correction/GuestCorrectionAuditLog.java` | Per-correction audit trail |
| `idx_gcal_action` | `guest_correction_audit_logs` | `action` | `GuestCorrectionAuditLog.java` | Action filtering |
| `idx_gcal_actor` | `guest_correction_audit_logs` | `actor_username` | `GuestCorrectionAuditLog.java` | Per-actor audit trail |
| `idx_gcal_occurred_at` | `guest_correction_audit_logs` | `occurred_at` | `GuestCorrectionAuditLog.java` | Time-window scans |
| `idx_guest_interaction_entity` | `guest_interaction_logs` | `entity_type, entity_code, interacted_at` | `platform/model/trending/GuestInteractionLog.java` | `findTrendingRaw` — the `WHERE interacted_at >= :sevenDaysAgo GROUP BY entity_type, entity_code` trending query |
| `idx_guest_interaction_time` | `guest_interaction_logs` | `interacted_at` | `GuestInteractionLog.java` | `deleteOlderThan` — the 3 AM cleanup job |
| `idx_guest_search_time` | `guest_search_logs` | `searched_at` | `platform/model/trending/GuestSearchLog.java` | `findTopSearches` window predicate and `deleteOlderThan` |
| `idx_guest_search_query` | `guest_search_logs` | `query` | `GuestSearchLog.java` | `findTopSearches` `GROUP BY query` |
| `idx_user_warnings_target` | `user_warnings` | `target_user_id, removed_at` | `user/model/UserWarning.java` | `GET /api/warnings/me` — a recipient's active warnings |
| `idx_user_warnings_actor` | `user_warnings` | `actor_user_id` | `UserWarning.java` | Warnings issued by one admin |
| `idx_user_warnings_created_at` | `user_warnings` | `created_at` | `UserWarning.java` | Newest-first ordering |
| `idx_user_warnings_acknowledged` | `user_warnings` | `target_user_id, acknowledged, removed_at` | `UserWarning.java` | The unacknowledged-warning badge on every authenticated request |

### 6. Unique constraints (each backed by a unique index)

Postgres implements a unique constraint with a unique btree index, so these are queryable indexes
too.

| Constraint / column | Table | Columns | Declared as |
|---|---|---|---|
| `uk_pm_code` | `physical_media` | `pm_code` | `@UniqueConstraint` on `PhysicalMedia` |
| `uk_pmt_name` | `physical_media_types` | `name` | `@UniqueConstraint` on `PhysicalMediaType` |
| `uk_maqam_teacher_one_vote_per_song` | `maqam_teacher_votes` | `list_of_maqam_id, teacher_user_id` | `@UniqueConstraint` on `MaqamTeacherVote` — enforces one vote per teacher per song |
| `uk_users_username` | `users_tbl` | `username` | `@UniqueConstraint` on `User` |
| `uk_users_email` | `users_tbl` | `email` | `@UniqueConstraint` on `User` |
| _(Hibernate-named)_ | `audios` | `audio_code` | `@Column(unique = true)` on `Audio.audioCode` |
| _(Hibernate-named)_ | `videos` | `video_code` | `@Column(unique = true)` on `Video.videoCode` |
| _(Hibernate-named)_ | `images` | `image_code` | `@Column(unique = true)` on `Image.imageCode` |
| _(Hibernate-named)_ | `texts` | `text_code` | `@Column(unique = true)` on `Text.textCode` |
| _(Hibernate-named)_ | `projects` | `project_code` | `@Column(unique = true)` on `Project.projectCode` |
| _(Hibernate-named)_ | `categories` | `category_code` | `@Column(unique = true)` on `Category.categoryCode` |
| _(Hibernate-named)_ | `person` | `person_code` | `@Column(unique = true)` on `Person.personCode` |
| _(Hibernate-named)_ | `list_of_maqam` | `maqam_code` | `@Column(unique = true)` on `ListOfMaqam.maqamCode` |
| _(Hibernate-named)_ | `physical_media` | `pm_code` | `@Column(unique = true)` on `PhysicalMedia.pmCode` — in addition to `uk_pm_code` |
| _(Hibernate-named)_ | `users_tbl` | `username`, `email` | `@Column(unique = true)` on `User.username` / `User.email` — in addition to `uk_users_username` / `uk_users_email` |
| _(Hibernate-named)_ | `token_blacklist` | `token` | `@Column(unique = true)` on `TokenBlacklist.token` |
| _(Hibernate-named)_ | `sessions` | `session_id` | `@Column(unique = true)` on `Session.sessionId` |

Constraints declared only via `@Column(unique = true)` get a generated name (`UK…`) that this
codebase never references. `_Not documented in source._` — read the real names from
`\d+ <table>` in `psql` if you need them.

---

## Hibernate tuning in `application.yaml`

> **Read the [inert keys](#eight-hibernate-keys-are-inert-verified) section before you use any
> number on this page.** Eight of the settings below are written at property paths that do not
> exist. They are verified inert: Hibernate never receives them. This is a live defect in the
> application, not a documentation quirk.

The persistence block, verbatim:

```yaml
  jpa:
    open-in-view: false
    show-sql: true
    hibernate:
      # Authentication revocation is backed by the sessions table. A
      # create/create-drop schema mode deletes every active login on restart.
      ddl-auto: update
      properties:
        hibernate:
          format_sql: true
          use_sql_comments: true
          dialect: org.hibernate.dialect.PostgreSQLDialect
          default_batch_fetch_size: 1000
    jdbc:
      time_zone: UTC
      batch_size: 100
      order_inserts: true
      order_updates: true
```

| Setting | Value | In effect? | What it was meant to buy |
|---|---|---|---|
| `default_batch_fetch_size` | `1000` | **No** | Intended as the N+1 fix on **reads**: collect up to 1000 pending proxies and resolve them with one `WHERE parent_id IN (?, ?, …)` instead of one `SELECT` per row. Not in effect — Hibernate's default batch size (no batching) applies. |
| `batch_size` | `100` | **No** | Intended as the N+1 fix on **writes**: group up to 100 `INSERT`/`UPDATE` statements into one JDBC batch and one round trip. Not in effect — every insert and update is its own round trip. |
| `order_inserts` | `true` | **No** | Intended to sort pending inserts by entity type before flushing so same-shape statements can fill a batch. Moot anyway while `batch_size` is inert. |
| `order_updates` | `true` | **No** | The same reordering for updates, and a deadlock-risk reduction. Also moot. |
| `format_sql` / `use_sql_comments` | `true` | **No** | Intended SQL-log formatting. Not in effect: logged statements are unformatted single lines with no JPQL/HQL comment prefix. Statements are still *logged*, via `logging.level.org.hibernate.SQL: DEBUG`. |
| `dialect` | `PostgreSQLDialect` | **No** | Not in effect, and harmless — Hibernate auto-detects the dialect from the JDBC connection metadata and resolves the same `PostgreSQLDialect`. |
| `time_zone` | `UTC` | **No** | Intended to bind all `Instant`/timestamp values in UTC. Not in effect — Hibernate binds in the JVM default zone instead. |
| `open-in-view` | `false` | Yes | Closes the persistence context at the end of the service call rather than holding it open through view rendering. A lazy association touched after the transaction throws `LazyInitializationException` instead of silently firing a query outside any transaction. Slow, invisible per-row queries during serialization become loud failures at development time. |
| `show-sql` | `true` | Yes | Prints each statement. This is the surviving half of the SQL-logging story. |
| `ddl-auto` | `update` | Yes | Schema evolution. Never `create`/`create-drop` — that would empty `sessions` and log every user out on restart. |

### Eight Hibernate keys are inert (verified)

`spring.jpa.hibernate.properties.*` and `spring.jpa.jdbc.*` are not property paths Spring Boot
binds. Verified against `META-INF/spring-configuration-metadata.json` in
`spring-boot-jpa-4.0.5.jar` and `spring-boot-hibernate-4.0.5.jar`: the only keys that exist under
`spring.jpa` are `database`, `database-platform`, `generate-ddl`, `mapping-resources`,
`open-in-view`, `properties`, `show-sql`, `hibernate.ddl-auto`, `hibernate.naming.*` and
`hibernate.use-new-id-generator-mappings`. There is **no** `spring.jpa.hibernate.properties` and
**no** `spring.jpa.jdbc`. Unknown keys under a `@ConfigurationProperties` type are ignored
silently rather than failing the boot, which is why a non-binding key looks identical to a working
one from the outside.

These eight are inert:

```
spring.jpa.hibernate.properties.hibernate.format_sql
spring.jpa.hibernate.properties.hibernate.use_sql_comments
spring.jpa.hibernate.properties.hibernate.dialect
spring.jpa.hibernate.properties.hibernate.default_batch_fetch_size   (1000)
spring.jpa.jdbc.time_zone                                            (UTC)
spring.jpa.jdbc.batch_size                                           (100)
spring.jpa.jdbc.order_inserts                                        (true)
spring.jpa.jdbc.order_updates                                        (true)
```

`spring.jpa.hibernate.ddl-auto`, `spring.jpa.show-sql` and `spring.jpa.open-in-view` sit on real
paths and do take effect. SQL statements still reach the log because
`logging.level.org.hibernate.SQL: DEBUG` is set independently under `logging:` — that is a logging
key, not a Hibernate key.

**Observable consequences right now:**

- **No batch fetching.** With `default_batch_fetch_size` inert, the ~26 `@ElementCollection`
  tables load one `SELECT` per parent row per association. Every read-cache miss that loads a full
  active list pays N+1 on the list endpoints.
- **No JDBC insert/update batching.** The bulk-create endpoints and the ~4,400-row `.xlsx`
  physical-media import issue one round trip per statement instead of batching 100 at a time.
- **Timestamps bind in the JVM default zone, not UTC.** With `jdbc.time_zone` inert, Hibernate
  uses whatever `TimeZone.getDefault()` returns on the host. Two hosts with different zones write
  different values for the same `Instant`. See
  [`./important-fields.md`](./important-fields.md#4-timestamps-and-time-zones).

**The corrected nesting** — everything belongs under `spring.jpa.properties.hibernate.*`, with
`time_zone` and `batch_size` under a nested `jdbc` block:

```yaml
  jpa:
    open-in-view: false
    show-sql: true
    hibernate:
      ddl-auto: update
    properties:
      hibernate:
        format_sql: true
        use_sql_comments: true
        default_batch_fetch_size: 1000
        # dialect: omit — Hibernate auto-detects PostgreSQLDialect from the connection
        jdbc:
          time_zone: UTC
          batch_size: 100
        order_inserts: true
        order_updates: true
```

(`order_inserts` / `order_updates` are `hibernate.order_inserts` / `hibernate.order_updates`, not
`hibernate.jdbc.*` — they sit beside the `jdbc:` block, not inside it.)

### The N+1 problem this codebase actually has

Every read cache loads a full active list and maps each row to a DTO, touching lazy collections as
it goes. `ProjectReadCache` spells out the arithmetic it *expects*:

```java
 * <p>On miss, one main query loads projects; lazy collections (categories,
 * tags, keywords, person, person.personType) are loaded via
 * {@code hibernate.default_batch_fetch_size=1000}, so for 1000 projects there
 * are at most ~5 small secondary queries — no N+1.
```

**That javadoc describes the intent, not the current behavior.** `default_batch_fetch_size` is
written at an inert path (see [above](#eight-hibernate-keys-are-inert-verified)), so Hibernate
never receives it. The real cost on a cold `ProjectReadCache` miss is 1000 projects × 5 lazy
associations ≈ 5000 extra round trips, not five. `ImageReadCache` carries the same claim for
`project` + `person` + `categories` + `subjects`/`genres`/`colors`/`usages`/`tags`/`keywords`, and
it is wrong in the same way and for the same reason. Fixing the YAML nesting is what makes both
javadocs true.

Two repository methods take the other approach — an explicit `JOIN FETCH` — because they load one
collection and want exactly one query:

```java
SELECT DISTINCT c FROM Category c
LEFT JOIN FETCH c.keywords
WHERE c.removedAt IS NULL
ORDER BY c.name ASC
```

(`CategoryRepository.findAllActiveWithKeywords`, and `PersonRepository.findAllActiveWithPersonType`
for `p.personType`.) `ProjectRepository` uses `@EntityGraph(attributePaths = {"categories", "person"})`
for the same reason, on four finders: `findByProjectCode`, `findByProjectCodeAndRemovedAtIsNull`,
`findAllByRemovedAtIsNotNull()` (the trash listing) and `findAllByPersonInAndRemovedAtIsNull`.

### Confirming the inert keys yourself

The finding above is settled, but it is cheap to re-check after any dependency bump. Two ways:

- **From the log.** `logging.level.org.hibernate.SQL=DEBUG` is already set. Run a bulk create and
  count the statements: one `INSERT` per row, unformatted and with no leading JPQL comment, is the
  current (broken) signature. Batched, formatted, comment-prefixed statements mean the nesting was
  fixed.
- **From the factory.** At boot, inspect `EntityManagerFactory#getProperties()` and look for
  `hibernate.default_batch_fetch_size` and `hibernate.jdbc.batch_size`. Absent today; present once
  the keys move to `spring.jpa.properties.hibernate.*`.

---

## The Caffeine read-cache layer

In-process Caffeine, not Redis. `CacheConfig` declares an explicit `SimpleCacheManager` bean with a
**fixed** list of caches, each built with its own size and TTL:

```java
private static CaffeineCache build(String name, long maxSize, long ttlMinutes) {
    return new CaffeineCache(name,
            Caffeine.newBuilder()
                    .maximumSize(maxSize)
                    .expireAfterWrite(ttlMinutes, TimeUnit.MINUTES)
                    .build());
}
```

Two consequences of that being a `SimpleCacheManager`:

- The cache list is closed. A `@Cacheable("something:new")` whose name is **not** in `CacheConfig`
  fails at runtime — `SimpleCacheManager` does not create caches on demand. Adding a cached method
  means adding its name to the list in the same change.
- Because an explicit `CacheManager` bean is defined, it wins over Spring Boot's auto-configuration;
  `spring.cache.type: caffeine` in `application.yaml` does not drive this configuration.

### Every cache

| Cache name | Max size | TTL | Holds | Populated by | Evicted by |
|---|---|---|---|---|---|
| `categories:all` | 1 | 10 min | Full active `List<CategoryResponseDTO>` | `CategoryReadCache.getAllActive()` | `CategoryReadCache.evictAll()` after any category mutation (also evicts `keywords:suggest`) |
| `audios:all` | 1 | 10 min | Full active `List<AudioResponseDTO>` | `AudioReadCache.getAllActive()` | `AudioReadCache.evictAll()` (also evicts `tags:suggest` + `keywords:suggest`) |
| `images:all` | 1 | 10 min | Full active `List<ImageResponseDTO>` | `ImageReadCache.getAllActive()` | `ImageReadCache.evictAll()` (also `tags:suggest` + `keywords:suggest`) |
| `videos:all` | 1 | 10 min | Full active `List<VideoResponseDTO>` | `VideoReadCache.getAllActive()` | `VideoReadCache.evictAll()` (also `tags:suggest` + `keywords:suggest`) |
| `texts:all` | 1 | 10 min | Full active `List<TextResponseDTO>` | `TextReadCache.getAllActive()` | `TextReadCache.evictAll()` (also `tags:suggest` + `keywords:suggest`) |
| `projects:all` | 1 | 10 min | Full active `List<ProjectResponseDTO>` | `ProjectReadCache.getAllActive()` | `ProjectReadCache.evictAll()` (also `tags:suggest` + `keywords:suggest`) |
| `persons:all` | 1 | 10 min | Full active `List<PersonResponseDTO>` | `PersonReadCache.getAllActive()` | `PersonReadCache.evictAll()` — the only read cache that does **not** cascade to the suggest caches. `Person.tag` and `Person.keywords` are scalar `@Column`s, not `@ElementCollection` tables, so they are not part of the `/suggest` vocabulary |
| `tags:suggest` | 1 000 | 10 min | One ranked suggestion list per `(canonicalQuery, limit)` pair | `TagSuggestService.lookup(canonical, limit)`, keyed `T(java.util.Objects).hash(#canonical, #limit)` | `TagSuggestService.evictAll()`, invoked from the `@Caching` block on all five tag-owning read caches |
| `keywords:suggest` | 1 000 | 10 min | Same, for keywords | `KeywordSuggestService.lookup(canonical, limit)` | `KeywordSuggestService.evictAll()`, invoked from all six keyword-owning read caches |
| `analytics:user.v2` | 200 | 5 min | `UserActivityDTO` per `username:filter:page:size:sort` | `AnalyticsService.getUserActivity(...)` | TTL only — no `@CacheEvict` anywhere |
| `analytics:overview.v2` | 50 | 5 min | `TeamOverviewDTO` per `filter:topN` | `AnalyticsService.getOverview(...)` | TTL only |
| `analytics:users.v2` | 50 | 5 min | `List<UserSummaryDTO>` per `filter` | `AnalyticsService.getUsers(...)` | TTL only |
| `users:details` | 500 | 1 min | `UserDetails` per username — removes a DB hit from every authenticated request | `UserService` (`@Cacheable(value = "users:details", key = "#username")`) | `@CacheEvict(allEntries = true)` on every mutating method in `UserService` and `AdminUserService` — role change, permission grant/revoke, activate/deactivate, etc. The 1-minute TTL is the backstop |
| `trending:results` | 1 | 5 min | `GuestTrendingDTO` — the full trending payload | `GuestTrendingService.getTrending()` | The 3 AM `purgeOldLogs()` job, plus TTL |
| `trending:snapshot` | 1 | 5 min | `Map<"type:code", TrendingMark>` used to stamp rank/score onto list rows without a second call | `GuestTrendingService.getSnapshot()` | The 3 AM `purgeOldLogs()` job, plus TTL |

`maximumSize = 1` on the `*:all` caches is deliberate — each holds exactly one entry (the whole
active list), so eviction never fires and the TTL is what bounds staleness. `CacheConfig` says so:

```java
 *   - "all-items" caches (categories, audios, …) hold ONE entry (the full
 *     active list) so maximumSize=1 is correct — eviction never fires in
 *     practice; TTL keeps data fresh after mutations.
```

### The cross-entity eviction fan-out

Tags and keywords are cross-entity: the same tag can live on an audio, a video and a project, and
`/api/tags/suggest` unions five tables while `/api/keywords/suggest` unions six. So each media read
cache evicts the suggest caches alongside its own:

```java
@Caching(evict = {
        @CacheEvict(value = ACTIVE_CACHE, allEntries = true),
        // Audio tag/keyword changes invalidate the cross-entity suggest caches.
        @CacheEvict(value = TagSuggestService.CACHE, allEntries = true),
        @CacheEvict(value = KeywordSuggestService.CACHE, allEntries = true)
})
public void evictAll() { }
```

`CategoryReadCache` evicts only `keywords:suggest` (Category has keywords, no tags).
`PersonReadCache` evicts neither.

One path bypasses this entirely: `VocabularyBulkRepository` runs global tag/keyword rename and
delete as native statements straight through the `EntityManager`, so Hibernate never sees them:

```java
 * <p>Because these bypass Hibernate (no L1/L2, no {@code @Version} bump), the
 * calling service must evict the affected read-caches afterwards.
```

If a bulk vocabulary operation leaves stale data visible, that eviction call is the first place to
look.

### Endpoints that deliberately bypass the cache

| Endpoint / path | Why it reads fresh from the DB |
|---|---|
| `GET /api/maqam` and the maqam trash listing, **when filter or sort params are supplied** | No maqam read cache exists at all. `MaqamService.listActive` sets `inMemory = params.hasActiveFilters() \|\| requiresInMemorySort(...) \|\| (teacher && sortPresent)`; on that branch it calls `maqamRepository.findAllByRemovedAtIsNull()` (or `findAssignedToTeacher(..., Pageable.unpaged())` for teachers), filters and sorts in memory, then slices. Unfiltered requests take the fast DB-paged `findAllByRemovedAtIsNull(pageable)` path. |
| `GET /api/physical-media` and its trash listing, **when filter params or a derived-key sort are supplied** | No physical-media read cache exists. `PhysicalMediaService.listActive` calls `repository.findAllByRemovedAtIsNullOrderByIdAsc()` on the `needsInMemory(params)` branch. Its javadoc: *"This entity is DB-paged (no read-cache), so the full-set load happens only here; the inventory is a few thousand rows, microseconds to scan."* |
| Filtered analytics requests | The `@Cacheable` annotations carry `condition = "#filter.isCacheable()"`. A non-default filter skips the cache and runs the indexed `all_logs` CTE directly. |
| `GET /api/analytics/actions`, correction stats | No `@Cacheable` — always live. |
| Every `searchByText` path (staff and guest) | Search results are never cached; they run against the indexes on every request. |

Both filter paths are two-branch by design: the empty-filter request stays on the fast DB-paged
query, and only a filtered or derived-key-sort request pays for the full-set load. The repository
methods that back the slow branch — `findAllByRemovedAtIsNullOrderByIdAsc` and
`findAllByRemovedAtIsNotNullOrderByIdAsc` on `PhysicalMediaRepository`, `findAllByRemovedAtIsNull()`
and `findAllByRemovedAtIsNotNull()` on `ListOfMaqamRepository` — exist for that branch alone and
their javadoc says so.

---

## Diagnosing a slow endpoint

### 1. Confirm the boot created the indexes you expect

```bash
grep -E "Skipped index|Failed to (create|ensure)|indexes ensured" app.log
```

Expect seven `… indexes ensured` lines across the four initializers — the exact strings are listed
in [How indexes get created](#how-indexes-get-created). Any `Skipped index` or `Failed to create`
line names an index that does not exist and a query that is therefore scanning.

Cross-check against the database:

```sql
SELECT indexname, tablename
  FROM pg_indexes
 WHERE schemaname = 'public'
   AND tablename = 'images'
 ORDER BY indexname;

-- Is the extension actually installed?
SELECT extname FROM pg_extension WHERE extname = 'pg_trgm';
```

### 2. Capture the statement

SQL logging is already configured — no code change needed:

```yaml
  jpa:
    show-sql: true
    hibernate:
      properties:
        hibernate:
          format_sql: true        # INERT — wrong path, see below
          use_sql_comments: true  # INERT — wrong path, see below
logging:
  level:
    org.hibernate.SQL: DEBUG
    org.hibernate.orm.jdbc.bind: TRACE
    org.springframework.cache: DEBUG
    org.springframework.jdbc: DEBUG
```

- `org.hibernate.SQL: DEBUG` prints each statement. This works — it is a `logging.level` key, not a
  Hibernate key.
- `org.hibernate.orm.jdbc.bind: TRACE` prints the bound parameter values, so you can reconstruct a
  runnable statement.
- `format_sql` and `use_sql_comments` are **not in effect** — they sit at
  `spring.jpa.hibernate.properties.hibernate.*`, a path Spring Boot does not bind (see
  [Eight Hibernate keys are inert](#eight-hibernate-keys-are-inert-verified)). Statements arrive as
  unformatted single lines with no JPQL comment prefix, so you cannot map a statement back to its
  repository method from the comment. Until the nesting is corrected, match on the table and
  predicate shape instead, or move the two keys to `spring.jpa.properties.hibernate.*` locally for
  the debugging session.
- `org.springframework.cache: DEBUG` shows cache hits and misses, which answers "is this endpoint
  even hitting the database?" before you tune anything.

Hit the endpoint once to warm caches, then again, and compare. If the second call issues no SQL, the
problem is a cold cache or an eviction storm, not an index.

Note what the log will *not* show: the native queries assembled by `MediaSearchSqlBuilder` and run
through `EntityManager.createNativeQuery` still appear under `org.hibernate.SQL`, but they carry no
HQL comment because there is no JPQL behind them. Recognize them by the `WITH t0_cands AS (` prefix.

### 3. Reproduce it in `psql`

```bash
curl -s -o /dev/null -w '%{time_total}\n' \
  "{{BASE_URL}}/api/image/search?q=hasan&limit=50" \
  -H "Cookie: khi_auth_token=$TOKEN"
```

Then paste the captured statement, with the bound values substituted, into `psql`.

### 4. `EXPLAIN ANALYZE` it and check the index actually used

```sql
EXPLAIN (ANALYZE, BUFFERS)
WITH cands AS (
    SELECT i.id
      FROM images i
     WHERE i.removed_at IS NULL
       AND ( LOWER(COALESCE(i.original_title, '')) LIKE '%hasan%' ESCAPE '\' )
    LIMIT 2000
)
SELECT i.* FROM images i JOIN cands ON cands.id = i.id LIMIT 50;
```

Read the plan for these signals:

| Signal in the plan | Reading |
|---|---|
| `Bitmap Index Scan on idx_images_original_title_trgm` | The GIN trigram index is doing its job |
| `Index Scan using idx_images_original_title_pat` | The `text_pattern_ops` btree is serving a prefix probe |
| `Seq Scan on images` with a large `rows removed by filter` | No index applied — go to step 5 |
| A tier-3 `SubPlan` per row over `image_tags` etc. | Check the child FK btree (`idx_image_tags_image_id`) exists |
| Actual rows at the CTE far below `LIMIT 2000` | The prefilter is not the bottleneck; the cost is in ranking or in the outer join |

To isolate whether the index *can* be used at all, force the planner's hand for one session:

```sql
SET enable_seqscan = off;
EXPLAIN ANALYZE <statement>;
RESET enable_seqscan;
```

If it still sequential-scans with `enable_seqscan = off`, the index genuinely cannot serve that
predicate — that is a query/index-expression mismatch, not a costing decision.

### 5. The usual fixes, in this codebase's order of likelihood

| Symptom | Fix |
|---|---|
| Sequential scan on a search column that *has* a `_trgm` index | Compare the query expression to the index expression. The index is on `LOWER(col)`; the query may test `LOWER(COALESCE(col, ''))`. Postgres matches expression indexes structurally — see [Verifying expression-index matching](#verifying-expression-index-matching). |
| Query shorter than 3 characters is slow | Expected: no trigram exists at that length. Check the column has a `_pat` btree, and that the query uses the prefix leg. |
| Whole search endpoint errors, not just slow | `pg_trgm` is missing — `%` and `similarity()` are undefined. Check `SELECT extname FROM pg_extension`. |
| Index missing entirely after a first-ever deploy | The table did not exist when the initializer ran. Restart once; the statements are `IF NOT EXISTS` and idempotent. |
| Slow *first* call, fast afterwards | Read-cache miss loading a full active list, running one query per row. `default_batch_fetch_size` is inert — the `~5 secondary queries` pattern the read-cache javadocs promise does not happen. Fix the [YAML nesting](#eight-hibernate-keys-are-inert-verified); there is no per-endpoint workaround short of `JOIN FETCH` / `@EntityGraph`. |
| Every call slow, cache never hits | A mutation on a hot path is calling `evictAll()`, or the endpoint is on a deliberate bypass path — check the [bypass table](#endpoints-that-deliberately-bypass-the-cache). |
| Bulk create / `.xlsx` import slow | JDBC batching is not in effect — `batch_size`, `order_inserts` and `order_updates` are all [inert](#eight-hibernate-keys-are-inert-verified). Expect one round trip per row until the nesting is corrected. |
| `LazyInitializationException` under load | `open-in-view: false` is doing its job. Fix the query (`JOIN FETCH` or `@EntityGraph`), do not re-enable the setting. |
| Analytics report slow | Confirm the three `idx_<table>_*` indexes exist on all ten CTE branch tables. One un-indexed branch scans the whole table inside the `UNION ALL`. |
| Physical-media search slow at scale | It has no trigram index by design. Adding `pg_trgm` coverage there is a schema change, not a tuning knob. |

### 6. What not to do

- Do not add an index by hand in `psql`. It will not survive a rebuild of the database and no other
  environment will have it. Add it to the matching `*IndexInitializer` so every environment converges.
- Do not switch `ddl-auto` away from `update` to force a schema change. `create` / `create-drop`
  empties `sessions` and logs every user out — the YAML comment says exactly this.
- Do not add a `@Cacheable` without adding its cache name to `CacheConfig`.

---

## Notes

**Table names.** Every table named in this document comes from an explicit `@Table(name = …)`,
`@CollectionTable(name = …)` or `@JoinTable(name = …)`, or is quoted verbatim from the SQL string in
an initializer. Three are worth flagging because they are not what the default naming strategy
would produce from the class name:

- `person` — singular, from `@Table(name = "person")` on `Person`. Not `persons`.
- `person_person_type` — from `@CollectionTable(name = "person_person_type")` on `Person.personType`.
- `users_tbl` — from `@Table(name = "users_tbl")` on `User`.

`list_of_maqam` matches what the default strategy would produce from `ListOfMaqam`, but the explicit
`@Table(name = "list_of_maqam")` is what is in force.

**No table name in this document was inferred.** Every one is either an explicit annotation value or
a literal quoted from initializer / repository SQL. `audio_subjects`, for instance, is both — a
`@CollectionTable(name = "audio_subjects")` on `Audio.subject` and a literal in
`AudioRepository.searchByText`.

**Column names.** Almost every column named here is explicit — from `@Column(name = …)`,
`@JoinColumn(name = …)`, an `@Index(columnList = …)` literal, or a literal inside an initializer's
or repository's SQL string. `version` is a case that looks like a default but is not: all seven
tables touched by `MediaSearchIndexInitializer.backfillNullVersions()` declare
`@Column(name = "version", nullable = false)` explicitly.

Exactly two names in this document were **inferred**, both with Hibernate's default
`CamelCaseToUnderscoresNamingStrategy` (camelCase → snake_case, lowercased):

- `id` — every `platform` entity declares `@Id @GeneratedValue(...) private Long id;` with no
  `@Column`, so the column is `id`. Used above by the phase-1 CTE join (`cands.id = i.id`) and by
  the child-collection FK index rows.
- `users_tbl.user_id` — `User` declares `private Long userId;` with no `@Column(name = …)`, giving
  `user_id` under the same rule. Corroborated by `MaqamTeacherVote`'s javadoc, which writes
  "FK to `users_tbl.user_id`" literally.

**Index counts.** 191 media + 33 audit-log + 3 category + 10 person = **237 indexes created by the
initializers**, plus 63 declared via `@Table(indexes = …)` and the unique indexes behind the
constraints listed above.

**Not documented in source.**

- `pg_trgm.similarity_threshold` is never set by the application, so the `%` operator uses the
  Postgres default. The explicit `similarity() > :threshold` predicates use application constants
  (`0.3` / `0.2`) instead.
- No `VACUUM` / `ANALYZE` / autovacuum tuning appears anywhere in the codebase or configuration.
- No connection-pool sizing (`spring.datasource.hikari.*`) is configured; Spring Boot defaults apply.
- No statement timeout, `work_mem`, or other Postgres GUC is set by the application.
- Generated names for the `@Column(unique = true)` constraints.

---

## Related

- [Database documentation index](./README.md)
- [Tags and keywords](../content/tags-and-keywords.md) — the `/suggest` endpoints backed by
  `tags:suggest` / `keywords:suggest`, and the bulk vocabulary path that bypasses Hibernate
- [Unified items endpoint](../content/items.md) — the back-office list that reads the four
  `*:all` read caches described above
