# Schema — Content Tables

> **Audience:** Backend / DBA · **Source:** `src/main/java/ak/dev/khi_archive_platform/platform/model/audio`,
> `.../model/video`, `.../model/image`, `.../model/text`, `.../model/category`, `.../model/person`,
> `.../model/project`, `.../model/khilogo`, `.../platform/enums`

These are the core archive tables: the four media entities (`audios`, `videos`, `images`, `texts`),
the project layer they hang off (`projects`) and the category vocabulary a project references
(`categories`), the biographical register (`person`), the branding row (`khi_logo`), and the 26
element-collection / join tables those eight entities declare. There is no Flyway or Liquibase — the
DDL comes from Hibernate `ddl-auto=update`, which also creates the indexes declared in
`@Table(indexes = ...)`, plus the `JdbcTemplate` initializers in `platform/config/`, which own every
trigram, `text_pattern_ops` and child-table index listed below.

## Tables at a glance

| Table | Java entity | Purpose | Rows grow with |
|---|---|---|---|
| `audios` | `Audio` | Audio recording metadata + S3 pointer | One per ingested audio file |
| `audio_genres` | `Audio.genre` | Genre labels for an audio row | Genres per audio |
| `audio_subjects` | `Audio.subject` | Subject labels for an audio row | Subjects per audio |
| `audio_contributors` | `Audio.contributors` | Contributor names for an audio row | Contributors per audio |
| `audio_tags` | `Audio.tags` | Canonicalized tags | Tags per audio |
| `audio_keywords` | `Audio.keywords` | Canonicalized keywords | Keywords per audio |
| `videos` | `Video` | Video metadata + S3 pointer | One per ingested video file |
| `video_subjects` | `Video.subject` | Subject labels | Subjects per video |
| `video_genres` | `Video.genre` | Genre labels | Genres per video |
| `video_colors` | `Video.colorOfVideo` | Color descriptors | Colors per video |
| `video_usages` | `Video.whereThisVideoUsed` | Where the video has been used | Usage entries per video |
| `video_tags` | `Video.tags` | Canonicalized tags | Tags per video |
| `video_keywords` | `Video.keywords` | Canonicalized keywords | Keywords per video |
| `images` | `Image` | Image metadata + S3 pointer | One per ingested image file |
| `image_subjects` | `Image.subject` | Subject labels | Subjects per image |
| `image_genres` | `Image.genre` | Genre labels | Genres per image |
| `image_colors` | `Image.colorOfImage` | Color descriptors | Colors per image |
| `image_usages` | `Image.whereThisImageUsed` | Where the image has been used | Usage entries per image |
| `image_tags` | `Image.tags` | Canonicalized tags | Tags per image |
| `image_keywords` | `Image.keywords` | Canonicalized keywords | Keywords per image |
| `texts` | `Text` | Document metadata + S3 pointers (file + cover) | One per ingested document |
| `text_subjects` | `Text.subject` | Subject labels | Subjects per text |
| `text_genres` | `Text.genre` | Genre labels | Genres per text |
| `text_tags` | `Text.tags` | Canonicalized tags | Tags per text |
| `text_keywords` | `Text.keywords` | Canonicalized keywords | Keywords per text |
| `categories` | `Category` | Controlled category vocabulary | One per curated category |
| `category_keywords` | `Category.keywords` | Alternative names used for dedupe | Keywords per category |
| `person` | `Person` | Biographical register, portrait pointer | One per archived person |
| `person_person_type` | `Person.personType` | Role labels for a person | Types per person |
| `projects` | `Project` | Collection that owns media rows | One per project/collection |
| `project_categories` | `Project.categories` | Project ↔ category many-to-many | Category links per project |
| `project_tags` | `Project.tags` | Canonicalized tags | Tags per project |
| `project_keywords` | `Project.keywords` | Canonicalized keywords | Keywords per project |
| `khi_logo` | `KhiLogo` | Institutional logo image pointer | One per uploaded logo |

## Shared column conventions

Seven of the eight entity tables (everything except `khi_logo`) repeat the same column families.
Read this section once instead of re-reading it in every table below.

### The `*_code` business key

| Table | Column | Type | Example shape |
|---|---|---|---|
| `audios` | `audio_code` | `VARCHAR(255)` `UNIQUE NOT NULL` | `HASAZIRA_AUD_RAW_V1_Copy(1)_000001` |
| `videos` | `video_code` | `VARCHAR(255)` `UNIQUE NOT NULL` | `HASAZIRA_VID_RAW_V1_Copy(1)_000001` |
| `images` | `image_code` | `VARCHAR(255)` `UNIQUE NOT NULL` | `HASAZIRA_IMG_RAW_V1_Copy(1)_000001` |
| `texts` | `text_code` | `VARCHAR(255)` `UNIQUE NOT NULL` | `HASAZIRA_TXT_RAW_V1_Copy(1)_000001` |
| `projects` | `project_code` | `VARCHAR(200)` `UNIQUE NOT NULL` | `PERSONCODE-PROJ-######` or `PROJECTNAME-PROJ-######` |
| `categories` | `category_code` | `VARCHAR(120)` `UNIQUE NOT NULL` | _Not documented in source._ |
| `person` | `person_code` | `VARCHAR(50)` `UNIQUE NOT NULL` | `HZI`, `AMA` |

Every API path over these seven tables addresses a row by its code, never by `id`; `khi_logo` is the
one table in this file addressed by `id`. The surrogate `id BIGINT` identity column is the physical
PK and the FK target; the code is the logical key. Both are unique, so either can be joined on —
prefer `id` for joins and `*_code` for lookups. Only the four media code columns (`audio_code`,
`video_code`, `image_code`, `text_code`) carry a GIN trigram index *and* a `text_pattern_ops` btree
index; `project_code`, `category_code` and `person_code` have nothing beyond the plain btree declared
on the entity (see the per-table Indexes sections).

### `removed_at` — the soft-trash marker

`DELETE` on a content resource does **not** remove the row. The trash state lives entirely in two
columns, and nothing in the database enforces it:

| State | SQL predicate | `removed_at` | `removed_by` |
|---|---|---|---|
| Active | `removed_at IS NULL` | `NULL` | `NULL` |
| Trashed | `removed_at IS NOT NULL` | trash instant | actor username, `VARCHAR(120)` |

Nothing else changes when a row is trashed — the title, the code, the `is_public` flag, the S3 URL
and every collection-table child row are left exactly as they were. Two consequences:

1. **Every query you write must add its own `AND removed_at IS NULL`.** There is no partial index,
   no view, and no `@Where` filter doing it for you. `ProjectService` documents this explicitly with
   its `activeMedia` / `trashedMedia` helpers.
2. A trashed row still occupies its unique `*_code`. Re-creating a record with the same code fails
   the unique constraint until the trashed row is permanently deleted.

Permanent deletion is a real `DELETE FROM`, admin-only, and rejected unless the row is already
trashed — `AudioService` raises `"Audio must be in trash before permanent deletion. Trash it first."`
The project-level cascade in `ProjectService` deletes child media rows with JPA `deleteAll(...)`,
not with a database `ON DELETE CASCADE`; no FK in this schema declares one.

Trash and restore on the project cascade path are issued as bulk JPQL, e.g. in `AudioRepository`:

```sql
UPDATE Audio a SET a.removedAt = :removedAt, a.removedBy = :removedBy,
       a.version = COALESCE(a.version, 0) + 1
 WHERE a.project = :project AND a.removedAt IS NULL
```

```sql
UPDATE Audio a SET a.removedAt = NULL, a.removedBy = NULL,
       a.version = COALESCE(a.version, 0) + 1
 WHERE a.project = :project AND a.removedAt IS NOT NULL
```

Restore therefore un-trashes **every** trashed media row under the project, including rows that were
trashed individually before the project itself was trashed.

### `is_public` / `is_visible_to_public` — visibility

| Table | Column | Definition |
|---|---|---|
| `audios`, `videos`, `images`, `texts` | `is_public` | `BOOLEAN NOT NULL DEFAULT TRUE` |
| `projects` | `is_visible_to_public` | `BOOLEAN NOT NULL DEFAULT TRUE` |

`false` hides the row from every `/api/guest/**` surface; staff endpoints ignore it. The two levels
are independent columns — a public audio inside a hidden project stays `is_public = true` in SQL. A
project update can *optionally* cascade its value down, via `AudioRepository.updateVisibilityByProject`
and its three siblings:

```sql
UPDATE Audio a SET a.isPublic = :isPublic,
       a.updatedAt = :updatedAt, a.updatedBy = :updatedBy,
       a.version = COALESCE(a.version, 0) + 1
 WHERE a.project = :project AND a.removedAt IS NULL
   AND (a.isPublic IS NULL OR a.isPublic <> :isPublic)
```

Note the `IS NULL` legs. Although the mapping is `nullable = false`, the field is a boxed `Boolean`
and repository count queries defensively treat `NULL` as public:

```sql
SELECT COUNT(v) FROM Video v WHERE v.removedAt IS NULL AND (v.isPublic IS NULL OR v.isPublic = true)
```

Copy that `IS NULL OR = true` shape in any new public-visibility query rather than writing
`is_public = true`, or rows written before the column existed will silently drop out of your counts.

### Audit columns

Present on all seven trashable tables with identical definitions:

| Column | Type | Written by |
|---|---|---|
| `created_at` | `TIMESTAMP(6) WITH TIME ZONE` | `@PrePersist` — set to `Instant.now()` only if still null |
| `updated_at` | `TIMESTAMP(6) WITH TIME ZONE` | `@PrePersist`, then `@PreUpdate` on every flush |
| `removed_at` | `TIMESTAMP(6) WITH TIME ZONE` | Service layer on trash; `NULL` on restore |
| `created_by` | `VARCHAR(120)` | Service layer (actor username) |
| `updated_by` | `VARCHAR(120)` | Service layer (actor username) |
| `removed_by` | `VARCHAR(120)` | Service layer on trash; `NULL` on restore |
| `version` | `BIGINT NOT NULL DEFAULT 0` | `@jakarta.persistence.Version` optimistic lock |

`version` is the optimistic-lock column. JPA bumps it on every save; a stale read raises
`ObjectOptimisticLockingFailureException`, which the exception handler translates to HTTP `409`. Bulk
JPQL updates bypass the automatic bump, so each one increments it by hand with
`COALESCE(version, 0) + 1`. Because the column arrived through `ddl-auto=update`, pre-existing rows
can hold `NULL`; `MediaSearchIndexInitializer.backfillNullVersions()` repairs them on every boot:

```sql
UPDATE audios   SET version = 0 WHERE version IS NULL
UPDATE videos   SET version = 0 WHERE version IS NULL
UPDATE images   SET version = 0 WHERE version IS NULL
UPDATE texts    SET version = 0 WHERE version IS NULL
UPDATE projects SET version = 0 WHERE version IS NULL
UPDATE person   SET version = 0 WHERE version IS NULL
UPDATE categories SET version = 0 WHERE version IS NULL
```

The loop swallows failures ("column may not exist yet on first boot"), so treat `version IS NULL` as
possible in hand-written SQL and always wrap it in `COALESCE`.

### S3 URL columns

| Table | Column | Type | Holds |
|---|---|---|---|
| `audios` | `audio_file_url` | `VARCHAR(1000)` | Audio object URL |
| `videos` | `video_file_url` | `VARCHAR(1000)` | Video object URL |
| `images` | `image_file_url` | `VARCHAR(1000)` | Image object URL |
| `texts` | `text_file_url` | `VARCHAR(1000)` | Document object URL |
| `texts` | `cover_image_url` | `VARCHAR(1000)` | Cover image object URL |
| `person` | `media_portrait` | `VARCHAR(255)` | Portrait object URL |
| `khi_logo` | `image_url` | `VARCHAR(500)` `NOT NULL` | Logo object URL |

The stored value is the absolute object URL built by `S3Service.getPublicUrl(key)`:

```java
return "https://" + bucket + ".s3." + region + ".amazonaws.com/" + key;
```

**These values never leave the API.** Each response mapper overwrites the field with a proxy path
before serialization — `/api/audio/{audioCode}/stream`, `/api/video/{videoCode}/stream`,
`/api/image/{imageCode}/view`, `/api/text/{textCode}/read`, `/api/text/{textCode}/cover`. If you are
debugging a broken media link, the DB column and the JSON field with the same name hold different
things.

Watch the width asymmetry: `person.media_portrait` is `VARCHAR(255)` while the media tables allow
`VARCHAR(1000)`. Keys are `{base-folder}/{folder}/{uuid}-{sanitized filename}`, so a long original
filename on a person portrait is the one place this schema can overflow.

### Type derivation rules used in this document

No naming strategy is configured in `application.yaml`, so Hibernate's Spring Boot default
(`CamelCaseToUnderscoresNamingStrategy`) applies wherever a name is not stated explicitly. Every
column, collection table and join table in this file **does** carry an explicit name, with one
exception: the `id` primary key of each entity, which has no `@Column`, so the field name `id` is
used verbatim. The join columns inside the collection and join tables are explicit
`@JoinColumn(name = ...)` values as well, so no inference is needed there either.

| Java | SQL |
|---|---|
| `Long id` + `@GeneratedValue(IDENTITY)` | `BIGINT` identity, `NOT NULL` |
| `String`, no `length`/`columnDefinition` | `VARCHAR(255)` |
| `String` + `length = N` | `VARCHAR(N)` |
| `String` + `columnDefinition = "TEXT"` | `TEXT` |
| `Integer` | `INTEGER` |
| `boolean` (primitive) | `BOOLEAN NOT NULL` |
| `Boolean` + `columnDefinition` | as written in `columnDefinition` |
| `Instant` | `TIMESTAMP(6) WITH TIME ZONE` |
| `LocalDate` | `DATE` |
| `Long` + `@Version` + `@ColumnDefault("0")` | `BIGINT NOT NULL DEFAULT 0` |

Timestamps serialize through Jackson in `Asia/Baghdad` (`spring.jackson.time-zone`); the stored value
is the UTC instant.

### Bag semantics on every collection table

All 26 collection and join tables map a Java `List` with no `@OrderColumn`. Hibernate treats that as
a **bag**: the table gets its owner FK column plus its value column and nothing else — no surrogate
key, no ordering column. Practical consequences for query authors:

- Row order is not guaranteed. `ORDER BY` on the value column if order matters.
- The database does not prevent the same value appearing twice for one owner. Uniqueness is enforced
  only in Java, by `Tags.canonical(...)` / `Keywords.canonical(...)`.
- Updating the collection makes Hibernate `DELETE` all rows for that owner and re-`INSERT` them.
- Generated PK/FK constraint names are Hibernate defaults and are not stated in source —
  `_Not documented in source._` wherever this file would otherwise name one.

### Tag and keyword canonicalization

Every `*_tags` and `*_keywords` table stores values already normalized by
`platform/service/common/Tags.java` and `Keywords.java` — both delegate to the shared
`Tags.TextListCanonicalizer`, which differs only in the length cap: NFKC normalize → replace
zero-width joiners with a space → trim → collapse internal whitespace → drop blanks and reject
anything longer than the cap → lower-case (`Locale.ROOT`) → deduplicate (first occurrence wins).
The caps are `Tags.MAX_TAG_LENGTH = 64` and `Keywords.MAX_KEYWORD_LENGTH = 200`; over-length values are
**rejected, not truncated**. The columns themselves are `TEXT` — the caps exist only in Java, so a
direct SQL `INSERT` can bypass them and produce values the autocomplete endpoints will not match.

---

## `audios`

**Entity:** `ak.dev.khi_archive_platform.platform.model.audio.Audio`

| Column | Type | Null | Default | Description |
|---|---|---|---|---|
| `id` | `BIGINT` identity | No | identity | **PK.** Field has no `@Column`; name defaults to `id` |
| `audio_code` | `VARCHAR(255)` | No | — | Business key, `UNIQUE`. e.g. `HASAZIRA_AUD_RAW_V1_Copy(1)_000001` |
| `project_id` | `BIGINT` | No | — | **FK → `projects.id`.** Every audio must belong to a project |
| `file_name` | `TEXT` | Yes | — | Original filename |
| `volume_name` | `VARCHAR(255)` | Yes | — | External volume the file came from |
| `directory_name` | `VARCHAR(255)` | Yes | — | Directory on that volume |
| `path_in_external` | `VARCHAR(512)` | Yes | — | Path within the external volume (Java field `path_in_external`) |
| `auto_path` | `VARCHAR(512)` | Yes | — | Derived path (Java field `auto_path`) |
| `origin_title` | `TEXT` | Yes | — | Original title |
| `alter_title` | `TEXT` | Yes | — | Alternative title |
| `central_kurdish_title` | `TEXT` | Yes | — | Title in Central Kurdish |
| `romanized_title` | `TEXT` | Yes | — | Romanized title |
| `form` | `TEXT` | Yes | — | Musical/spoken form |
| `type_of_basta` | `VARCHAR(255)` | Yes | — | Basta type |
| `type_of_maqam` | `VARCHAR(255)` | Yes | — | Maqam type as catalogued here (unrelated to the `list_of_maqam` voting table) |
| `abstract_text` | `TEXT` | Yes | — | Abstract (Java field `abstractText`) |
| `description` | `TEXT` | Yes | — | Free description |
| `speaker` | `TEXT` | Yes | — | Speaker name(s) |
| `singer` | `TEXT` | Yes | — | Singer name(s) |
| `producer` | `TEXT` | Yes | — | Producer name(s) |
| `composer` | `TEXT` | Yes | — | Composer name(s) |
| `language` | `VARCHAR(255)` | Yes | — | Language |
| `dialect` | `VARCHAR(255)` | Yes | — | Dialect |
| `type_of_composition` | `VARCHAR(255)` | Yes | — | Composition type |
| `type_of_performance` | `VARCHAR(255)` | Yes | — | Performance type |
| `lyrics` | `TEXT` | Yes | — | Lyrics |
| `poet` | `TEXT` | Yes | — | Poet |
| `recording_venue` | `VARCHAR(255)` | Yes | — | Venue (Java field `recording_venue`) |
| `city` | `VARCHAR(255)` | Yes | — | City |
| `region` | `VARCHAR(255)` | Yes | — | Region |
| `date_created` | `TIMESTAMP(6) WITH TIME ZONE` | Yes | — | Content creation date (not the row's `created_at`) |
| `date_published` | `TIMESTAMP(6) WITH TIME ZONE` | Yes | — | Publication date |
| `date_modified` | `TIMESTAMP(6) WITH TIME ZONE` | Yes | — | Content modification date |
| `audience` | `VARCHAR(255)` | Yes | — | Intended audience |
| `physical_availability` | `BOOLEAN` | No | — | Primitive `boolean`; no DB default, Hibernate always writes it |
| `physical_label` | `TEXT` | Yes | — | Label on the physical carrier |
| `location_archive` | `TEXT` | Yes | — | Shelf location |
| `degitized_by` | `TEXT` | Yes | — | Who digitized it (spelling as in source) |
| `degitization_equipment` | `TEXT` | Yes | — | Equipment used (spelling as in source) |
| `audio_file_note` | `TEXT` | Yes | — | Note about the file |
| `audio_channel` | `VARCHAR(100)` | Yes | — | Channel layout |
| `file_extension` | `VARCHAR(50)` | Yes | — | File extension |
| `file_size` | `VARCHAR(100)` | Yes | — | Stored as text, not a number |
| `duration` | `VARCHAR(100)` | Yes | — | Stored as text, not an interval |
| `bit_rate` | `VARCHAR(100)` | Yes | — | Bit rate |
| `bit_depth` | `VARCHAR(100)` | Yes | — | Bit depth |
| `sample_rate` | `VARCHAR(100)` | Yes | — | Sample rate |
| `audio_quality_out_of_10` | `INTEGER` | Yes | — | Subjective quality score |
| `audio_version` | `VARCHAR(255)` | Yes | — | Version label (RAW, MASTER, …) |
| `version_number` | `INTEGER` | Yes | — | Version number (unrelated to `version`) |
| `copy_number` | `INTEGER` | Yes | — | Copy number |
| `lcc_classification` | `VARCHAR(255)` | Yes | — | LCC class (Java field `lcc_classification`) |
| `accrual_method` | `VARCHAR(255)` | Yes | — | How the item was accrued |
| `provenance` | `TEXT` | Yes | — | Provenance |
| `copyright` | `TEXT` | Yes | — | Copyright statement |
| `right_owner` | `TEXT` | Yes | — | Rights owner |
| `date_copyrighted` | `TIMESTAMP(6) WITH TIME ZONE` | Yes | — | Copyright date |
| `availability` | `VARCHAR(255)` | Yes | — | Availability statement |
| `license_type` | `VARCHAR(255)` | Yes | — | License type |
| `usage_rights` | `TEXT` | Yes | — | Usage rights |
| `owner` | `TEXT` | Yes | — | Owner |
| `publisher` | `TEXT` | Yes | — | Publisher |
| `archive_local_note` | `TEXT` | Yes | — | Internal archive note |
| `audio_file_url` | `VARCHAR(1000)` | Yes | — | S3 object URL — see [S3 URL columns](#s3-url-columns) |
| `is_public` | `BOOLEAN NOT NULL DEFAULT TRUE` | No | `TRUE` | Guest visibility |
| `created_at` | `TIMESTAMP(6) WITH TIME ZONE` | Yes | — | Row creation |
| `updated_at` | `TIMESTAMP(6) WITH TIME ZONE` | Yes | — | Last write |
| `removed_at` | `TIMESTAMP(6) WITH TIME ZONE` | Yes | — | Soft-trash marker |
| `created_by` | `VARCHAR(120)` | Yes | — | Actor username |
| `updated_by` | `VARCHAR(120)` | Yes | — | Actor username |
| `removed_by` | `VARCHAR(120)` | Yes | — | Actor username at trash time |
| `version` | `BIGINT` | No | `0` | `@Version` optimistic lock |

**Keys and constraints**

- **PK:** `id`.
- **Unique:** `audio_code` (`@Column(unique = true)`); constraint name is Hibernate-generated —
  _Not documented in source._
- **FK:** `project_id` → `projects.id`, `NOT NULL`, no on-delete behavior declared (default
  `NO ACTION`). Project-level deletes are performed application-side in `ProjectService`.
- **NOT NULL:** `id`, `audio_code`, `project_id`, `physical_availability`, `is_public`, `version`.
- **CHECK:** none — this entity has no enum-typed column.

**Indexes**

Declared on `@Table(indexes = ...)` and created by Hibernate:

| Index | Columns |
|---|---|
| `idx_audio_code` | `audio_code` |
| `idx_audio_project_id` | `project_id` |
| `idx_audio_removed_at` | `removed_at` |

Created by `MediaSearchIndexInitializer.ensureAudioIndexes()` on `ApplicationReadyEvent`, after
`CREATE EXTENSION IF NOT EXISTS pg_trgm`. Two helpers build every statement:

```sql
CREATE INDEX IF NOT EXISTS <name> ON <table> USING GIN (LOWER(<column>) gin_trgm_ops)
CREATE INDEX IF NOT EXISTS <name> ON <table> (LOWER(<column>) text_pattern_ops)
```

GIN trigram (`idx_audios_<x>_trgm`) on: `audio_code`, `file_name`, `volume_name`, `directory_name`,
`path_in_external`, `auto_path`, `origin_title`, `alter_title`, `central_kurdish_title`,
`romanized_title`, `form`, `type_of_basta`, `type_of_maqam`, `abstract_text`, `description`,
`speaker`, `producer`, `composer`, `language`, `dialect`, `lyrics`, `poet`, `recording_venue`,
`city`, `region`, `provenance`, `audio_file_note`.

Btree `text_pattern_ops` (`idx_audios_<x>_pat`) on: `audio_code`, `file_name`, `origin_title`,
`alter_title`, `central_kurdish_title`, `romanized_title`, `speaker`, `composer`, `poet`, `producer`,
`city`, `region`, `type_of_basta`, `type_of_maqam`.

**Relationships**

| Field | Kind | Target | Fetch | Cascade / orphanRemoval | Table |
|---|---|---|---|---|---|
| `project` | `@ManyToOne` | `Project` | `LAZY` | none | FK column `project_id` |
| `genre` | `@ElementCollection` | `String` | `LAZY` | implicit (owned) | `audio_genres` |
| `subject` | `@ElementCollection` | `String` | `LAZY` | implicit (owned) | `audio_subjects` |
| `contributors` | `@ElementCollection` | `String` | `LAZY` | implicit (owned) | `audio_contributors` |
| `tags` | `@ElementCollection` | `String` | `LAZY` | implicit (owned) | `audio_tags` |
| `keywords` | `@ElementCollection` | `String` | `LAZY` | implicit (owned) | `audio_keywords` |

There is no inverse `@OneToMany` from `Project` to `Audio` — the association is unidirectional, so
counting a project's audios means querying `audios` by `project_id`.

**Notes**

- `singer` has **no** trigram or pattern index even though `speaker`, `composer` and `producer` do.
  A search across `singer` is a sequential scan; any column absent from the two lists above is.
- `file_size`, `duration`, `bit_rate`, `bit_depth`, `sample_rate` and `audio_channel` are all
  `VARCHAR`. Do not `ORDER BY duration` and expect numeric ordering.
- The Java field names `path_in_external`, `auto_path`, `central_kurdish_title`, `romanized_title`,
  `recording_venue` and `lcc_classification` are already snake_case in Java. The column names are
  explicit in `@Column(name = ...)` regardless, so no naming-strategy inference was needed.
- `date_created` / `date_modified` / `date_published` describe the *content*; `created_at` /
  `updated_at` describe the *row*. Filtering the wrong pair is the most common query bug here.
- `type_of_maqam` on this table is a free-text catalog field. The teacher voting workflow lives in a
  separate `list_of_maqam` table with its own uploaded audio file and no FK to `audios`.

## `audio_genres`

**Entity:** `ak.dev.khi_archive_platform.platform.model.audio.Audio` (field `genre`)

| Column | Type | Null | Default | Description |
|---|---|---|---|---|
| `audio_id` | `BIGINT` | No | — | **FK → `audios.id`.** Owner |
| `genre` | `TEXT` | Yes | — | One genre label |

**Keys and constraints** — No PK (bag mapping). FK `audio_id` → `audios.id`; constraint name
_Not documented in source._ No unique constraint, no CHECK.

**Indexes** — created by `MediaSearchIndexInitializer.ensureAudioIndexes()`:

| Index | Definition |
|---|---|
| `idx_audio_genres_genre_trgm` | `GIN (LOWER(genre) gin_trgm_ops)` |
| `idx_audio_genres_genre_pat` | `(LOWER(genre) text_pattern_ops)` |
| `idx_audio_genres_audio_id` | `(audio_id)` btree |

**Relationships** — `@ElementCollection(fetch = LAZY)` owned by `Audio`, `@CollectionTable(name = "audio_genres", joinColumns = @JoinColumn(name = "audio_id"))`, `@Column(name = "genre", columnDefinition = "TEXT")`. No mapped-by side; `Audio` is the sole owner.

**Notes** — Not canonicalized by `Tags`/`Keywords`; genres are stored as supplied. Join to `audios`
and re-apply `audios.removed_at IS NULL` yourself — the child row survives trashing.

## `audio_subjects`

**Entity:** `ak.dev.khi_archive_platform.platform.model.audio.Audio` (field `subject`)

| Column | Type | Null | Default | Description |
|---|---|---|---|---|
| `audio_id` | `BIGINT` | No | — | **FK → `audios.id`.** Owner |
| `subject` | `TEXT` | Yes | — | One subject label |

**Keys and constraints** — No PK (bag mapping). FK `audio_id` → `audios.id`; constraint name
_Not documented in source._

**Indexes** — **None.** `MediaSearchIndexInitializer` indexes `audio_genres`, `audio_contributors`,
`audio_tags` and `audio_keywords` but omits `audio_subjects`, unlike `image_subjects`,
`video_subjects` and `text_subjects`, which all get trigram, pattern and FK indexes.

**Relationships** — `@ElementCollection(fetch = LAZY)`, `@CollectionTable(name = "audio_subjects", joinColumns = @JoinColumn(name = "audio_id"))`, `@Column(name = "subject", columnDefinition = "TEXT")`.

**Notes** — Because there is not even a btree on `audio_id`, a per-row subject subquery over this
table is a sequential scan. Treat it as the slow leg of any audio search that touches subjects.

## `audio_contributors`

**Entity:** `ak.dev.khi_archive_platform.platform.model.audio.Audio` (field `contributors`)

| Column | Type | Null | Default | Description |
|---|---|---|---|---|
| `audio_id` | `BIGINT` | No | — | **FK → `audios.id`.** Owner |
| `contributor` | `TEXT` | Yes | — | One contributor name |

**Keys and constraints** — No PK (bag mapping). FK `audio_id` → `audios.id`; constraint name
_Not documented in source._

**Indexes** — `idx_audio_contributors_contributor_trgm` (`GIN (LOWER(contributor) gin_trgm_ops)`),
`idx_audio_contributors_contributor_pat` (`(LOWER(contributor) text_pattern_ops)`),
`idx_audio_contributors_audio_id` (`(audio_id)`), all from `MediaSearchIndexInitializer`.

**Relationships** — `@ElementCollection(fetch = LAZY)`, `@CollectionTable(name = "audio_contributors", joinColumns = @JoinColumn(name = "audio_id"))`, `@Column(name = "contributor", columnDefinition = "TEXT")`.

**Notes** — Audio is the only media entity that models contributors as a collection. `videos` and
`images` use a single `contributor` `TEXT` column, `texts` uses `contributors` `TEXT`.

## `audio_tags`

**Entity:** `ak.dev.khi_archive_platform.platform.model.audio.Audio` (field `tags`)

| Column | Type | Null | Default | Description |
|---|---|---|---|---|
| `audio_id` | `BIGINT` | No | — | **FK → `audios.id`.** Owner |
| `tag` | `TEXT` | Yes | — | One canonicalized tag, ≤ 64 chars |

**Keys and constraints** — No PK (bag mapping). FK `audio_id` → `audios.id`; constraint name
_Not documented in source._

**Indexes** — `idx_audio_tags_tag_trgm`, `idx_audio_tags_tag_pat`, `idx_audio_tags_audio_id`, from
`MediaSearchIndexInitializer`.

**Relationships** — `@ElementCollection(fetch = LAZY)`, `@CollectionTable(name = "audio_tags", joinColumns = @JoinColumn(name = "audio_id"))`, `@Column(name = "tag", columnDefinition = "TEXT")`.

**Notes** — One of the five tables the tag autocomplete unions. Values are already lower-cased, so
`LOWER(tag) = LOWER(:q)` is redundant but harmless (and matches the index expression).

## `audio_keywords`

**Entity:** `ak.dev.khi_archive_platform.platform.model.audio.Audio` (field `keywords`)

| Column | Type | Null | Default | Description |
|---|---|---|---|---|
| `audio_id` | `BIGINT` | No | — | **FK → `audios.id`.** Owner |
| `keyword` | `TEXT` | Yes | — | One canonicalized keyword, ≤ 200 chars |

**Keys and constraints** — No PK (bag mapping). FK `audio_id` → `audios.id`; constraint name
_Not documented in source._

**Indexes** — `idx_audio_keywords_keyword_trgm`, `idx_audio_keywords_keyword_pat`,
`idx_audio_keywords_audio_id`, from `MediaSearchIndexInitializer`.

**Relationships** — `@ElementCollection(fetch = LAZY)`, `@CollectionTable(name = "audio_keywords", joinColumns = @JoinColumn(name = "audio_id"))`, `@Column(name = "keyword", columnDefinition = "TEXT")`.

**Notes** — Keywords are phrases, not labels; multi-word values are expected.

---

## `videos`

**Entity:** `ak.dev.khi_archive_platform.platform.model.video.Video`

| Column | Type | Null | Default | Description |
|---|---|---|---|---|
| `id` | `BIGINT` identity | No | identity | **PK** |
| `video_code` | `VARCHAR(255)` | No | — | Business key, `UNIQUE`. e.g. `HASAZIRA_VID_RAW_V1_Copy(1)_000001` |
| `project_id` | `BIGINT` | No | — | **FK → `projects.id`** |
| `file_name` | `TEXT` | Yes | — | Original filename |
| `volume_name` | `VARCHAR(255)` | Yes | — | External volume |
| `directory` | `VARCHAR(255)` | Yes | — | Directory on that volume |
| `path_in_external_volume` | `VARCHAR(512)` | Yes | — | Path within the volume |
| `auto_path` | `VARCHAR(512)` | Yes | — | Derived path |
| `original_title` | `TEXT` | Yes | — | Original title |
| `alternative_title` | `TEXT` | Yes | — | Alternative title |
| `title_in_central_kurdish` | `TEXT` | Yes | — | Central Kurdish title |
| `romanized_title` | `TEXT` | Yes | — | Romanized title |
| `event` | `TEXT` | Yes | — | Event depicted |
| `location` | `TEXT` | Yes | — | Location depicted |
| `description` | `TEXT` | Yes | — | Free description |
| `person_shown_in_video` | `TEXT` | Yes | — | People shown (free text, not an FK to `person`) |
| `video_version` | `VARCHAR(255)` | Yes | — | RAW, MASTER, RESTORED, ARCHIVE, ORIGINAL, 4K_MASTER, PROFESSIONAL (documented in the Javadoc; **not** an enum and not constrained) |
| `version_number` | `INTEGER` | Yes | — | Version number |
| `copy_number` | `INTEGER` | Yes | — | Copy number |
| `file_size` | `VARCHAR(100)` | Yes | — | Text, not numeric |
| `extension` | `VARCHAR(50)` | Yes | — | File extension |
| `orientation` | `VARCHAR(50)` | Yes | — | Orientation |
| `dimension` | `VARCHAR(100)` | Yes | — | Pixel dimensions |
| `resolution` | `VARCHAR(100)` | Yes | — | Resolution |
| `duration` | `VARCHAR(100)` | Yes | — | Text, not an interval |
| `bit_depth` | `VARCHAR(100)` | Yes | — | Bit depth |
| `frame_rate` | `VARCHAR(100)` | Yes | — | Frame rate |
| `overall_bit_rate` | `VARCHAR(100)` | Yes | — | Overall bit rate |
| `video_codec` | `VARCHAR(100)` | Yes | — | Video codec |
| `audio_codec` | `VARCHAR(100)` | Yes | — | Audio codec |
| `audio_channels` | `VARCHAR(100)` | Yes | — | Audio channel layout |
| `language` | `VARCHAR(255)` | Yes | — | Language |
| `dialect` | `VARCHAR(255)` | Yes | — | Dialect |
| `region` | `VARCHAR(255)` | Yes | — | Region |
| `subtitle` | `VARCHAR(255)` | Yes | — | Subtitle language/track |
| `creator_artist_director` | `TEXT` | Yes | — | Creator / artist / director |
| `producer` | `TEXT` | Yes | — | Producer |
| `contributor` | `TEXT` | Yes | — | Contributor (single column, not a collection) |
| `audience` | `VARCHAR(255)` | Yes | — | Intended audience |
| `accrual_method` | `VARCHAR(255)` | Yes | — | Accrual method |
| `provenance` | `TEXT` | Yes | — | Provenance |
| `video_status` | `VARCHAR(255)` | Yes | — | Free-text status |
| `archive_cataloging` | `TEXT` | Yes | — | Cataloging note |
| `physical_availability` | `BOOLEAN` | No | — | Primitive `boolean` |
| `physical_label` | `TEXT` | Yes | — | Physical carrier label |
| `location_in_archive_room` | `TEXT` | Yes | — | Shelf location |
| `lcc_classification` | `VARCHAR(255)` | Yes | — | LCC class |
| `note` | `TEXT` | Yes | — | Free note |
| `date_created` | `TIMESTAMP(6) WITH TIME ZONE` | Yes | — | Content creation date |
| `date_modified` | `TIMESTAMP(6) WITH TIME ZONE` | Yes | — | Content modification date |
| `date_published` | `TIMESTAMP(6) WITH TIME ZONE` | Yes | — | Publication date |
| `copyright` | `TEXT` | Yes | — | Copyright statement |
| `right_owner` | `TEXT` | Yes | — | Rights owner |
| `date_copyrighted` | `TIMESTAMP(6) WITH TIME ZONE` | Yes | — | Copyright date |
| `license_type` | `VARCHAR(255)` | Yes | — | License type |
| `usage_rights` | `TEXT` | Yes | — | Usage rights |
| `availability` | `VARCHAR(255)` | Yes | — | Availability statement |
| `owner` | `TEXT` | Yes | — | Owner |
| `publisher` | `TEXT` | Yes | — | Publisher |
| `video_file_url` | `VARCHAR(1000)` | Yes | — | S3 object URL |
| `is_public` | `BOOLEAN NOT NULL DEFAULT TRUE` | No | `TRUE` | Guest visibility |
| `created_at` | `TIMESTAMP(6) WITH TIME ZONE` | Yes | — | Row creation |
| `updated_at` | `TIMESTAMP(6) WITH TIME ZONE` | Yes | — | Last write |
| `removed_at` | `TIMESTAMP(6) WITH TIME ZONE` | Yes | — | Soft-trash marker |
| `created_by` | `VARCHAR(120)` | Yes | — | Actor username |
| `updated_by` | `VARCHAR(120)` | Yes | — | Actor username |
| `removed_by` | `VARCHAR(120)` | Yes | — | Actor username at trash time |
| `version` | `BIGINT` | No | `0` | `@Version` optimistic lock |

**Keys and constraints**

- **PK:** `id`. **Unique:** `video_code`.
- **FK:** `project_id` → `projects.id`, `NOT NULL`, no on-delete behavior declared.
- **NOT NULL:** `id`, `video_code`, `project_id`, `physical_availability`, `is_public`, `version`.
- **CHECK:** none — no enum-typed column.

**Indexes**

From `@Table(indexes = ...)`: `idx_video_code` (`video_code`), `idx_video_project_id`
(`project_id`), `idx_video_removed_at` (`removed_at`).

From `MediaSearchIndexInitializer.ensureVideoIndexes()` —
GIN trigram (`idx_videos_<x>_trgm`) on: `video_code`, `file_name`, `volume_name`, `directory`,
`path_in_external_volume`, `auto_path`, `original_title`, `alternative_title`,
`title_in_central_kurdish`, `romanized_title`, `event`, `location`, `description`,
`person_shown_in_video`, `resolution`, `video_codec`, `subtitle`, `creator_artist_director`,
`producer`, `contributor`, `provenance`, `note`.

Btree `text_pattern_ops` (`idx_videos_<x>_pat`) on: `video_code`, `file_name`, `original_title`,
`alternative_title`, `title_in_central_kurdish`, `romanized_title`, `creator_artist_director`,
`producer`, `event`, `person_shown_in_video`.

**Relationships**

| Field | Kind | Target | Fetch | Cascade / orphanRemoval | Table |
|---|---|---|---|---|---|
| `project` | `@ManyToOne` | `Project` | `LAZY` | none | FK column `project_id` |
| `subject` | `@ElementCollection` | `String` | `LAZY` | implicit (owned) | `video_subjects` |
| `genre` | `@ElementCollection` | `String` | `LAZY` | implicit (owned) | `video_genres` |
| `colorOfVideo` | `@ElementCollection` | `String` | `LAZY` | implicit (owned) | `video_colors` |
| `whereThisVideoUsed` | `@ElementCollection` | `String` | `LAZY` | implicit (owned) | `video_usages` |
| `tags` | `@ElementCollection` | `String` | `LAZY` | implicit (owned) | `video_tags` |
| `keywords` | `@ElementCollection` | `String` | `LAZY` | implicit (owned) | `video_keywords` |

Unidirectional from `Video` in every case; there is no inverse mapping on `Project`.

**Notes**

- The Javadoc lists RAW / MASTER / RESTORED / ARCHIVE / ORIGINAL / 4K_MASTER / PROFESSIONAL for
  `video_version`, but the column is a plain `VARCHAR(255)` with no CHECK and no enum. Any string can
  be stored; validate in the service layer, not in SQL.
- `language`, `dialect`, `region`, `archive_cataloging`, `physical_label` and
  `location_in_archive_room` have no trigram index. Substring searches over them scan.
- `person_shown_in_video` is free text. There is no FK from `videos` to `person`.

## `video_subjects`

**Entity:** `ak.dev.khi_archive_platform.platform.model.video.Video` (field `subject`)

| Column | Type | Null | Default | Description |
|---|---|---|---|---|
| `video_id` | `BIGINT` | No | — | **FK → `videos.id`.** Owner |
| `subject` | `TEXT` | Yes | — | One subject label |

**Keys and constraints** — No PK (bag mapping). FK `video_id` → `videos.id`; constraint name
_Not documented in source._

**Indexes** — `idx_video_subjects_subject_trgm` (GIN trigram), `idx_video_subjects_subject_pat`
(btree `text_pattern_ops`), `idx_video_subjects_video_id` (btree), all from
`MediaSearchIndexInitializer`.

**Relationships** — `@ElementCollection(fetch = LAZY)`, `@CollectionTable(name = "video_subjects", joinColumns = @JoinColumn(name = "video_id"))`, `@Column(name = "subject", columnDefinition = "TEXT")`.

**Notes** — Unlike `audio_subjects`, this table is fully indexed.

## `video_genres`

**Entity:** `ak.dev.khi_archive_platform.platform.model.video.Video` (field `genre`)

| Column | Type | Null | Default | Description |
|---|---|---|---|---|
| `video_id` | `BIGINT` | No | — | **FK → `videos.id`.** Owner |
| `genre` | `TEXT` | Yes | — | One genre label |

**Keys and constraints** — No PK (bag mapping). FK `video_id` → `videos.id`; constraint name
_Not documented in source._

**Indexes** — `idx_video_genres_genre_trgm`, `idx_video_genres_genre_pat`,
`idx_video_genres_video_id`, from `MediaSearchIndexInitializer`.

**Relationships** — `@ElementCollection(fetch = LAZY)`, `@CollectionTable(name = "video_genres", joinColumns = @JoinColumn(name = "video_id"))`, `@Column(name = "genre", columnDefinition = "TEXT")`.

**Notes** — Not canonicalized; free-text genre values.

## `video_colors`

**Entity:** `ak.dev.khi_archive_platform.platform.model.video.Video` (field `colorOfVideo`)

| Column | Type | Null | Default | Description |
|---|---|---|---|---|
| `video_id` | `BIGINT` | No | — | **FK → `videos.id`.** Owner |
| `color` | `TEXT` | Yes | — | One color descriptor |

**Keys and constraints** — No PK (bag mapping). FK `video_id` → `videos.id`; constraint name
_Not documented in source._

**Indexes** — `idx_video_colors_color_trgm` (GIN trigram) and `idx_video_colors_video_id` (btree).
There is **no** `text_pattern_ops` index on `color` — prefix search on this column is not
index-driven for 1–2 character queries.

**Relationships** — `@ElementCollection(fetch = LAZY)`, `@CollectionTable(name = "video_colors", joinColumns = @JoinColumn(name = "video_id"))`, `@Column(name = "color", columnDefinition = "TEXT")`.

**Notes** — The Java field is `colorOfVideo` but the column is explicitly named `color`; the table
name is explicitly `video_colors`. No naming-strategy inference applies.

## `video_usages`

**Entity:** `ak.dev.khi_archive_platform.platform.model.video.Video` (field `whereThisVideoUsed`)

| Column | Type | Null | Default | Description |
|---|---|---|---|---|
| `video_id` | `BIGINT` | No | — | **FK → `videos.id`.** Owner |
| `usage_context` | `TEXT` | Yes | — | One place the video has been used |

**Keys and constraints** — No PK (bag mapping). FK `video_id` → `videos.id`; constraint name
_Not documented in source._

**Indexes** — `idx_video_usages_usage_trgm` (GIN trigram on `usage_context`) and
`idx_video_usages_video_id` (btree). No `text_pattern_ops` index.

**Relationships** — `@ElementCollection(fetch = LAZY)`, `@CollectionTable(name = "video_usages", joinColumns = @JoinColumn(name = "video_id"))`, `@Column(name = "usage_context", columnDefinition = "TEXT")`.

**Notes** — Field name, column name and table name all differ (`whereThisVideoUsed` →
`usage_context` → `video_usages`). Do not derive any of the three from the others.

## `video_tags`

**Entity:** `ak.dev.khi_archive_platform.platform.model.video.Video` (field `tags`)

| Column | Type | Null | Default | Description |
|---|---|---|---|---|
| `video_id` | `BIGINT` | No | — | **FK → `videos.id`.** Owner |
| `tag` | `TEXT` | Yes | — | One canonicalized tag, ≤ 64 chars |

**Keys and constraints** — No PK (bag mapping). FK `video_id` → `videos.id`; constraint name
_Not documented in source._

**Indexes** — `idx_video_tags_tag_trgm`, `idx_video_tags_tag_pat`, `idx_video_tags_video_id`.

**Relationships** — `@ElementCollection(fetch = LAZY)`, `@CollectionTable(name = "video_tags", joinColumns = @JoinColumn(name = "video_id"))`, `@Column(name = "tag", columnDefinition = "TEXT")`.

**Notes** — Unioned by the tag autocomplete alongside `audio_tags`, `image_tags`, `text_tags` and
`project_tags`.

## `video_keywords`

**Entity:** `ak.dev.khi_archive_platform.platform.model.video.Video` (field `keywords`)

| Column | Type | Null | Default | Description |
|---|---|---|---|---|
| `video_id` | `BIGINT` | No | — | **FK → `videos.id`.** Owner |
| `keyword` | `TEXT` | Yes | — | One canonicalized keyword, ≤ 200 chars |

**Keys and constraints** — No PK (bag mapping). FK `video_id` → `videos.id`; constraint name
_Not documented in source._

**Indexes** — `idx_video_keywords_keyword_trgm`, `idx_video_keywords_keyword_pat`,
`idx_video_keywords_video_id`.

**Relationships** — `@ElementCollection(fetch = LAZY)`, `@CollectionTable(name = "video_keywords", joinColumns = @JoinColumn(name = "video_id"))`, `@Column(name = "keyword", columnDefinition = "TEXT")`.

**Notes** — One of the six tables the keyword autocomplete unions (the five media/project keyword
tables plus `category_keywords`).

---

## `images`

**Entity:** `ak.dev.khi_archive_platform.platform.model.image.Image`

| Column | Type | Null | Default | Description |
|---|---|---|---|---|
| `id` | `BIGINT` identity | No | identity | **PK** |
| `image_code` | `VARCHAR(255)` | No | — | Business key, `UNIQUE`. e.g. `HASAZIRA_IMG_RAW_V1_Copy(1)_000001` |
| `project_id` | `BIGINT` | No | — | **FK → `projects.id`** |
| `file_name` | `TEXT` | Yes | — | Original filename |
| `volume_name` | `VARCHAR(255)` | Yes | — | External volume |
| `directory` | `VARCHAR(255)` | Yes | — | Directory on that volume |
| `path_in_external_volume` | `VARCHAR(512)` | Yes | — | Path within the volume |
| `auto_path` | `VARCHAR(512)` | Yes | — | Derived path |
| `original_title` | `TEXT` | Yes | — | Original title |
| `alternative_title` | `TEXT` | Yes | — | Alternative title |
| `title_in_central_kurdish` | `TEXT` | Yes | — | Central Kurdish title |
| `romanized_title` | `TEXT` | Yes | — | Romanized title |
| `form` | `TEXT` | Yes | — | Form |
| `event` | `TEXT` | Yes | — | Event depicted |
| `location` | `TEXT` | Yes | — | Location depicted |
| `description` | `TEXT` | Yes | — | Free description |
| `person_shown_in_image` | `TEXT` | Yes | — | People shown (free text, not an FK to `person`) |
| `image_version` | `VARCHAR(255)` | Yes | — | Version label |
| `version_number` | `INTEGER` | Yes | — | Version number |
| `copy_number` | `INTEGER` | Yes | — | Copy number |
| `file_size` | `VARCHAR(100)` | Yes | — | Text, not numeric |
| `extension` | `VARCHAR(50)` | Yes | — | File extension |
| `orientation` | `VARCHAR(50)` | Yes | — | Orientation |
| `dimension` | `VARCHAR(100)` | Yes | — | Pixel dimensions |
| `bit_depth` | `VARCHAR(100)` | Yes | — | Bit depth |
| `dpi` | `VARCHAR(100)` | Yes | — | DPI |
| `manufacturer` | `VARCHAR(255)` | Yes | — | Camera manufacturer |
| `model` | `VARCHAR(255)` | Yes | — | Camera model |
| `lens` | `VARCHAR(255)` | Yes | — | Lens |
| `language` | `VARCHAR(255)` | Yes | — | Language |
| `dialect` | `VARCHAR(255)` | Yes | — | Dialect |
| `region` | `VARCHAR(255)` | Yes | — | Region |
| `creator_artist_photographer` | `TEXT` | Yes | — | Creator / artist / photographer |
| `contributor` | `TEXT` | Yes | — | Contributor (single column, not a collection) |
| `audience` | `VARCHAR(255)` | Yes | — | Intended audience |
| `accrual_method` | `VARCHAR(255)` | Yes | — | Accrual method |
| `provenance` | `TEXT` | Yes | — | Provenance |
| `photostory` | `TEXT` | Yes | — | Photo-story narrative |
| `image_status` | `VARCHAR(255)` | Yes | — | Free-text status |
| `archive_cataloging` | `TEXT` | Yes | — | Cataloging note |
| `physical_availability` | `BOOLEAN` | No | — | Primitive `boolean` |
| `physical_label` | `TEXT` | Yes | — | Physical carrier label |
| `location_in_archive_room` | `TEXT` | Yes | — | Shelf location |
| `lcc_classification` | `VARCHAR(255)` | Yes | — | LCC class |
| `note` | `TEXT` | Yes | — | Free note |
| `date_created` | `TIMESTAMP(6) WITH TIME ZONE` | Yes | — | Content creation date |
| `date_modified` | `TIMESTAMP(6) WITH TIME ZONE` | Yes | — | Content modification date |
| `date_published` | `TIMESTAMP(6) WITH TIME ZONE` | Yes | — | Publication date |
| `copyright` | `TEXT` | Yes | — | Copyright statement |
| `right_owner` | `TEXT` | Yes | — | Rights owner |
| `date_copyrighted` | `TIMESTAMP(6) WITH TIME ZONE` | Yes | — | Copyright date |
| `license_type` | `VARCHAR(255)` | Yes | — | License type |
| `usage_rights` | `TEXT` | Yes | — | Usage rights |
| `availability` | `VARCHAR(255)` | Yes | — | Availability statement |
| `owner` | `TEXT` | Yes | — | Owner |
| `publisher` | `TEXT` | Yes | — | Publisher |
| `image_file_url` | `VARCHAR(1000)` | Yes | — | S3 object URL |
| `is_public` | `BOOLEAN NOT NULL DEFAULT TRUE` | No | `TRUE` | Guest visibility |
| `created_at` | `TIMESTAMP(6) WITH TIME ZONE` | Yes | — | Row creation |
| `updated_at` | `TIMESTAMP(6) WITH TIME ZONE` | Yes | — | Last write |
| `removed_at` | `TIMESTAMP(6) WITH TIME ZONE` | Yes | — | Soft-trash marker |
| `created_by` | `VARCHAR(120)` | Yes | — | Actor username |
| `updated_by` | `VARCHAR(120)` | Yes | — | Actor username |
| `removed_by` | `VARCHAR(120)` | Yes | — | Actor username at trash time |
| `version` | `BIGINT` | No | `0` | `@Version` optimistic lock |

**Keys and constraints**

- **PK:** `id`. **Unique:** `image_code`.
- **FK:** `project_id` → `projects.id`, `NOT NULL`, no on-delete behavior declared.
- **NOT NULL:** `id`, `image_code`, `project_id`, `physical_availability`, `is_public`, `version`.
- **CHECK:** none — no enum-typed column.

**Indexes**

From `@Table(indexes = ...)`: `idx_image_code` (`image_code`), `idx_image_project_id`
(`project_id`), `idx_image_removed_at` (`removed_at`).

From `MediaSearchIndexInitializer.ensureImageIndexes()` —
GIN trigram (`idx_images_<x>_trgm`) on: `image_code`, `file_name`, `volume_name`, `directory`,
`path_in_external_volume`, `auto_path`, `original_title`, `alternative_title`,
`title_in_central_kurdish`, `romanized_title`, `form`, `event`, `location`, `description`,
`person_shown_in_image`, `creator_artist_photographer`, `contributor`, `provenance`, `photostory`,
`archive_cataloging`, `physical_label`, `location_in_archive_room`, `note`.

Btree `text_pattern_ops` (`idx_images_<x>_pat`) on: `image_code`, `file_name`, `original_title`,
`alternative_title`, `title_in_central_kurdish`, `romanized_title`,
`creator_artist_photographer`, `person_shown_in_image`, `event`.

**Relationships**

| Field | Kind | Target | Fetch | Cascade / orphanRemoval | Table |
|---|---|---|---|---|---|
| `project` | `@ManyToOne` | `Project` | `LAZY` | none | FK column `project_id` |
| `subject` | `@ElementCollection` | `String` | `LAZY` | implicit (owned) | `image_subjects` |
| `genre` | `@ElementCollection` | `String` | `LAZY` | implicit (owned) | `image_genres` |
| `colorOfImage` | `@ElementCollection` | `String` | `LAZY` | implicit (owned) | `image_colors` |
| `whereThisImageUsed` | `@ElementCollection` | `String` | `LAZY` | implicit (owned) | `image_usages` |
| `tags` | `@ElementCollection` | `String` | `LAZY` | implicit (owned) | `image_tags` |
| `keywords` | `@ElementCollection` | `String` | `LAZY` | implicit (owned) | `image_keywords` |

**Notes**

- In `Image` — and in `Text` — the `is_public` field sits under a comment header reading `Audit`,
  where `Audio` and `Video` put it under a `Visibility` header. The mapping is identical in all four.
- `manufacturer`, `model`, `lens`, `language`, `dialect` and `region` have no trigram index.
- Guests read image bytes through `/api/guest/image/{code}/view`; `image_file_url` is never returned.

## `image_subjects`

**Entity:** `ak.dev.khi_archive_platform.platform.model.image.Image` (field `subject`)

| Column | Type | Null | Default | Description |
|---|---|---|---|---|
| `image_id` | `BIGINT` | No | — | **FK → `images.id`.** Owner |
| `subject` | `TEXT` | Yes | — | One subject label |

**Keys and constraints** — No PK (bag mapping). FK `image_id` → `images.id`; constraint name
_Not documented in source._

**Indexes** — `idx_image_subjects_subject_trgm`, `idx_image_subjects_subject_pat`,
`idx_image_subjects_image_id`, from `MediaSearchIndexInitializer`.

**Relationships** — `@ElementCollection(fetch = LAZY)`, `@CollectionTable(name = "image_subjects", joinColumns = @JoinColumn(name = "image_id"))`, `@Column(name = "subject", columnDefinition = "TEXT")`.

**Notes** — None beyond the shared bag semantics.

## `image_genres`

**Entity:** `ak.dev.khi_archive_platform.platform.model.image.Image` (field `genre`)

| Column | Type | Null | Default | Description |
|---|---|---|---|---|
| `image_id` | `BIGINT` | No | — | **FK → `images.id`.** Owner |
| `genre` | `TEXT` | Yes | — | One genre label |

**Keys and constraints** — No PK (bag mapping). FK `image_id` → `images.id`; constraint name
_Not documented in source._

**Indexes** — `idx_image_genres_genre_trgm`, `idx_image_genres_genre_pat`,
`idx_image_genres_image_id`.

**Relationships** — `@ElementCollection(fetch = LAZY)`, `@CollectionTable(name = "image_genres", joinColumns = @JoinColumn(name = "image_id"))`, `@Column(name = "genre", columnDefinition = "TEXT")`.

**Notes** — Not canonicalized; free-text genre values.

## `image_colors`

**Entity:** `ak.dev.khi_archive_platform.platform.model.image.Image` (field `colorOfImage`)

| Column | Type | Null | Default | Description |
|---|---|---|---|---|
| `image_id` | `BIGINT` | No | — | **FK → `images.id`.** Owner |
| `color` | `TEXT` | Yes | — | One color descriptor |

**Keys and constraints** — No PK (bag mapping). FK `image_id` → `images.id`; constraint name
_Not documented in source._

**Indexes** — `idx_image_colors_color_trgm` (GIN trigram) and `idx_image_colors_image_id` (btree).
No `text_pattern_ops` index.

**Relationships** — `@ElementCollection(fetch = LAZY)`, `@CollectionTable(name = "image_colors", joinColumns = @JoinColumn(name = "image_id"))`, `@Column(name = "color", columnDefinition = "TEXT")`.

**Notes** — Java field `colorOfImage`, column `color`, table `image_colors` — all three explicit.

## `image_usages`

**Entity:** `ak.dev.khi_archive_platform.platform.model.image.Image` (field `whereThisImageUsed`)

| Column | Type | Null | Default | Description |
|---|---|---|---|---|
| `image_id` | `BIGINT` | No | — | **FK → `images.id`.** Owner |
| `usage_context` | `TEXT` | Yes | — | One place the image has been used |

**Keys and constraints** — No PK (bag mapping). FK `image_id` → `images.id`; constraint name
_Not documented in source._

**Indexes** — `idx_image_usages_usage_trgm` (GIN trigram on `usage_context`) and
`idx_image_usages_image_id` (btree). No `text_pattern_ops` index.

**Relationships** — `@ElementCollection(fetch = LAZY)`, `@CollectionTable(name = "image_usages", joinColumns = @JoinColumn(name = "image_id"))`, `@Column(name = "usage_context", columnDefinition = "TEXT")`.

**Notes** — Mirrors `video_usages` exactly, including the field/column/table name divergence.

## `image_tags`

**Entity:** `ak.dev.khi_archive_platform.platform.model.image.Image` (field `tags`)

| Column | Type | Null | Default | Description |
|---|---|---|---|---|
| `image_id` | `BIGINT` | No | — | **FK → `images.id`.** Owner |
| `tag` | `TEXT` | Yes | — | One canonicalized tag, ≤ 64 chars |

**Keys and constraints** — No PK (bag mapping). FK `image_id` → `images.id`; constraint name
_Not documented in source._

**Indexes** — `idx_image_tags_tag_trgm`, `idx_image_tags_tag_pat`, `idx_image_tags_image_id`.

**Relationships** — `@ElementCollection(fetch = LAZY)`, `@CollectionTable(name = "image_tags", joinColumns = @JoinColumn(name = "image_id"))`, `@Column(name = "tag", columnDefinition = "TEXT")`.

**Notes** — Unioned by the tag autocomplete.

## `image_keywords`

**Entity:** `ak.dev.khi_archive_platform.platform.model.image.Image` (field `keywords`)

| Column | Type | Null | Default | Description |
|---|---|---|---|---|
| `image_id` | `BIGINT` | No | — | **FK → `images.id`.** Owner |
| `keyword` | `TEXT` | Yes | — | One canonicalized keyword, ≤ 200 chars |

**Keys and constraints** — No PK (bag mapping). FK `image_id` → `images.id`; constraint name
_Not documented in source._

**Indexes** — `idx_image_keywords_keyword_trgm`, `idx_image_keywords_keyword_pat`,
`idx_image_keywords_image_id`.

**Relationships** — `@ElementCollection(fetch = LAZY)`, `@CollectionTable(name = "image_keywords", joinColumns = @JoinColumn(name = "image_id"))`, `@Column(name = "keyword", columnDefinition = "TEXT")`.

**Notes** — Unioned by the keyword autocomplete.

---

## `texts`

**Entity:** `ak.dev.khi_archive_platform.platform.model.text.Text`

| Column | Type | Null | Default | Description |
|---|---|---|---|---|
| `id` | `BIGINT` identity | No | identity | **PK** |
| `text_code` | `VARCHAR(255)` | No | — | Business key, `UNIQUE`. e.g. `HASAZIRA_TXT_RAW_V1_Copy(1)_000001` |
| `project_id` | `BIGINT` | No | — | **FK → `projects.id`** |
| `file_name` | `TEXT` | Yes | — | Original filename |
| `volume_name` | `VARCHAR(255)` | Yes | — | External volume |
| `directory` | `VARCHAR(255)` | Yes | — | Directory on that volume |
| `path_in_external_volume` | `VARCHAR(512)` | Yes | — | Path within the volume |
| `auto_path` | `VARCHAR(512)` | Yes | — | Derived path |
| `original_title` | `TEXT` | Yes | — | Original title |
| `alternative_title` | `TEXT` | Yes | — | Alternative title |
| `title_in_central_kurdish` | `TEXT` | Yes | — | Central Kurdish title |
| `romanized_title` | `TEXT` | Yes | — | Romanized title |
| `document_type` | `VARCHAR(255)` | Yes | — | Document type |
| `description` | `TEXT` | Yes | — | Free description |
| `script` | `VARCHAR(255)` | Yes | — | Writing script |
| `transcription` | `TEXT` | Yes | — | Transcribed content |
| `isbn` | `VARCHAR(255)` | Yes | — | ISBN (no uniqueness enforced) |
| `assignment_number` | `VARCHAR(255)` | Yes | — | Assignment number |
| `edition` | `VARCHAR(255)` | Yes | — | Edition |
| `volume` | `VARCHAR(255)` | Yes | — | Volume designation (not the storage volume — that is `volume_name`) |
| `series` | `VARCHAR(255)` | Yes | — | Series |
| `text_version` | `VARCHAR(255)` | Yes | — | Version label |
| `version_number` | `INTEGER` | Yes | — | Version number |
| `copy_number` | `INTEGER` | Yes | — | Copy number |
| `file_size` | `VARCHAR(100)` | Yes | — | Text, not numeric |
| `extension` | `VARCHAR(50)` | Yes | — | File extension |
| `orientation` | `VARCHAR(50)` | Yes | — | Orientation |
| `page_count` | `INTEGER` | Yes | — | Page count — the one genuinely numeric technical field |
| `size` | `VARCHAR(255)` | Yes | — | Size description |
| `physical_dimensions` | `VARCHAR(255)` | Yes | — | Physical dimensions |
| `language` | `VARCHAR(255)` | Yes | — | Language |
| `dialect` | `VARCHAR(255)` | Yes | — | Dialect |
| `region` | `VARCHAR(255)` | Yes | — | Region |
| `author` | `TEXT` | Yes | — | Author |
| `contributors` | `TEXT` | Yes | — | Contributors (single column, not a collection) |
| `printing_house` | `VARCHAR(255)` | Yes | — | Printing house |
| `audience` | `VARCHAR(255)` | Yes | — | Intended audience |
| `accrual_method` | `VARCHAR(255)` | Yes | — | Accrual method |
| `provenance` | `TEXT` | Yes | — | Provenance |
| `text_status` | `VARCHAR(255)` | Yes | — | Free-text status |
| `archive_cataloging` | `TEXT` | Yes | — | Cataloging note |
| `physical_availability` | `BOOLEAN` | No | — | Primitive `boolean` |
| `physical_label` | `TEXT` | Yes | — | Physical carrier label |
| `location_in_archive_room` | `TEXT` | Yes | — | Shelf location |
| `lcc_classification` | `VARCHAR(255)` | Yes | — | LCC class |
| `note` | `TEXT` | Yes | — | Free note |
| `date_created` | `TIMESTAMP(6) WITH TIME ZONE` | Yes | — | Content creation date |
| `print_date` | `TIMESTAMP(6) WITH TIME ZONE` | Yes | — | Printing date — unique to this table |
| `date_modified` | `TIMESTAMP(6) WITH TIME ZONE` | Yes | — | Content modification date |
| `date_published` | `TIMESTAMP(6) WITH TIME ZONE` | Yes | — | Publication date |
| `copyright` | `TEXT` | Yes | — | Copyright statement |
| `right_owner` | `TEXT` | Yes | — | Rights owner |
| `date_copyrighted` | `TIMESTAMP(6) WITH TIME ZONE` | Yes | — | Copyright date |
| `license_type` | `VARCHAR(255)` | Yes | — | License type |
| `usage_rights` | `TEXT` | Yes | — | Usage rights |
| `availability` | `VARCHAR(255)` | Yes | — | Availability statement |
| `owner` | `TEXT` | Yes | — | Owner |
| `publisher` | `TEXT` | Yes | — | Publisher |
| `text_file_url` | `VARCHAR(1000)` | Yes | — | S3 object URL of the document |
| `cover_image_url` | `VARCHAR(1000)` | Yes | — | S3 object URL of the cover image |
| `is_public` | `BOOLEAN NOT NULL DEFAULT TRUE` | No | `TRUE` | Guest visibility |
| `created_at` | `TIMESTAMP(6) WITH TIME ZONE` | Yes | — | Row creation |
| `updated_at` | `TIMESTAMP(6) WITH TIME ZONE` | Yes | — | Last write |
| `removed_at` | `TIMESTAMP(6) WITH TIME ZONE` | Yes | — | Soft-trash marker |
| `created_by` | `VARCHAR(120)` | Yes | — | Actor username |
| `updated_by` | `VARCHAR(120)` | Yes | — | Actor username |
| `removed_by` | `VARCHAR(120)` | Yes | — | Actor username at trash time |
| `version` | `BIGINT` | No | `0` | `@Version` optimistic lock |

**Keys and constraints**

- **PK:** `id`. **Unique:** `text_code`.
- **FK:** `project_id` → `projects.id`, `NOT NULL`, no on-delete behavior declared.
- **NOT NULL:** `id`, `text_code`, `project_id`, `physical_availability`, `is_public`, `version`.
- **CHECK:** none — no enum-typed column.

**Indexes**

From `@Table(indexes = ...)`: `idx_text_code` (`text_code`), `idx_text_project_id` (`project_id`),
`idx_text_removed_at` (`removed_at`).

From `MediaSearchIndexInitializer.ensureTextIndexes()` —
GIN trigram (`idx_texts_<x>_trgm`) on: `text_code`, `file_name`, `volume_name`, `directory`,
`path_in_external_volume`, `auto_path`, `original_title`, `alternative_title`,
`title_in_central_kurdish`, `romanized_title`, `document_type`, `description`, `script`,
`transcription`, `isbn`, `language`, `dialect`, `author`, `contributors`, `printing_house`,
`provenance`, `note`.

Btree `text_pattern_ops` (`idx_texts_<x>_pat`) on: `text_code`, `file_name`, `original_title`,
`alternative_title`, `title_in_central_kurdish`, `romanized_title`, `author`, `isbn`.

**Relationships**

| Field | Kind | Target | Fetch | Cascade / orphanRemoval | Table |
|---|---|---|---|---|---|
| `project` | `@ManyToOne` | `Project` | `LAZY` | none | FK column `project_id` |
| `subject` | `@ElementCollection` | `String` | `LAZY` | implicit (owned) | `text_subjects` |
| `genre` | `@ElementCollection` | `String` | `LAZY` | implicit (owned) | `text_genres` |
| `tags` | `@ElementCollection` | `String` | `LAZY` | implicit (owned) | `text_tags` |
| `keywords` | `@ElementCollection` | `String` | `LAZY` | implicit (owned) | `text_keywords` |

`texts` declares no colors and no usages collection — it has four child tables, not six.

**Notes**

- `transcription` is `TEXT` and carries a GIN trigram index; on a large corpus this is the biggest
  index on the table. Consider that before bulk-loading transcriptions.
- Two S3 columns. Permanent deletion of a text (or of its project) deletes both objects —
  `ProjectService` calls `deleteStoredFile` on `getTextFileUrl()` and `getCoverImageUrl()`.
- `isbn` is indexed but not unique; duplicate ISBNs are allowed.
- `volume` and `volume_name` mean different things. `volume` is the bibliographic volume;
  `volume_name` is the external storage volume the file was ingested from.

## `text_subjects`

**Entity:** `ak.dev.khi_archive_platform.platform.model.text.Text` (field `subject`)

| Column | Type | Null | Default | Description |
|---|---|---|---|---|
| `text_id` | `BIGINT` | No | — | **FK → `texts.id`.** Owner |
| `subject` | `TEXT` | Yes | — | One subject label |

**Keys and constraints** — No PK (bag mapping). FK `text_id` → `texts.id`; constraint name
_Not documented in source._

**Indexes** — `idx_text_subjects_subject_trgm`, `idx_text_subjects_subject_pat`,
`idx_text_subjects_text_id`, from `MediaSearchIndexInitializer`.

**Relationships** — `@ElementCollection(fetch = LAZY)`, `@CollectionTable(name = "text_subjects", joinColumns = @JoinColumn(name = "text_id"))`, `@Column(name = "subject", columnDefinition = "TEXT")`.

**Notes** — None beyond the shared bag semantics.

## `text_genres`

**Entity:** `ak.dev.khi_archive_platform.platform.model.text.Text` (field `genre`)

| Column | Type | Null | Default | Description |
|---|---|---|---|---|
| `text_id` | `BIGINT` | No | — | **FK → `texts.id`.** Owner |
| `genre` | `TEXT` | Yes | — | One genre label |

**Keys and constraints** — No PK (bag mapping). FK `text_id` → `texts.id`; constraint name
_Not documented in source._

**Indexes** — `idx_text_genres_genre_trgm`, `idx_text_genres_genre_pat`, `idx_text_genres_text_id`.

**Relationships** — `@ElementCollection(fetch = LAZY)`, `@CollectionTable(name = "text_genres", joinColumns = @JoinColumn(name = "text_id"))`, `@Column(name = "genre", columnDefinition = "TEXT")`.

**Notes** — Not canonicalized; free-text genre values.

## `text_tags`

**Entity:** `ak.dev.khi_archive_platform.platform.model.text.Text` (field `tags`)

| Column | Type | Null | Default | Description |
|---|---|---|---|---|
| `text_id` | `BIGINT` | No | — | **FK → `texts.id`.** Owner |
| `tag` | `TEXT` | Yes | — | One canonicalized tag, ≤ 64 chars |

**Keys and constraints** — No PK (bag mapping). FK `text_id` → `texts.id`; constraint name
_Not documented in source._

**Indexes** — `idx_text_tags_tag_trgm`, `idx_text_tags_tag_pat`, `idx_text_tags_text_id`.

**Relationships** — `@ElementCollection(fetch = LAZY)`, `@CollectionTable(name = "text_tags", joinColumns = @JoinColumn(name = "text_id"))`, `@Column(name = "tag", columnDefinition = "TEXT")`.

**Notes** — Unioned by the tag autocomplete.

## `text_keywords`

**Entity:** `ak.dev.khi_archive_platform.platform.model.text.Text` (field `keywords`)

| Column | Type | Null | Default | Description |
|---|---|---|---|---|
| `text_id` | `BIGINT` | No | — | **FK → `texts.id`.** Owner |
| `keyword` | `TEXT` | Yes | — | One canonicalized keyword, ≤ 200 chars |

**Keys and constraints** — No PK (bag mapping). FK `text_id` → `texts.id`; constraint name
_Not documented in source._

**Indexes** — `idx_text_keywords_keyword_trgm`, `idx_text_keywords_keyword_pat`,
`idx_text_keywords_text_id`.

**Relationships** — `@ElementCollection(fetch = LAZY)`, `@CollectionTable(name = "text_keywords", joinColumns = @JoinColumn(name = "text_id"))`, `@Column(name = "keyword", columnDefinition = "TEXT")`.

**Notes** — Unioned by the keyword autocomplete.

---

## `categories`

**Entity:** `ak.dev.khi_archive_platform.platform.model.category.Category`

| Column | Type | Null | Default | Description |
|---|---|---|---|---|
| `id` | `BIGINT` identity | No | identity | **PK** |
| `category_code` | `VARCHAR(120)` | No | — | Business key, `UNIQUE` |
| `name` | `TEXT` | No | — | Display name |
| `description` | `TEXT` | Yes | — | Description |
| `created_at` | `TIMESTAMP(6) WITH TIME ZONE` | Yes | — | Row creation |
| `updated_at` | `TIMESTAMP(6) WITH TIME ZONE` | Yes | — | Last write |
| `removed_at` | `TIMESTAMP(6) WITH TIME ZONE` | Yes | — | Soft-trash marker |
| `created_by` | `VARCHAR(120)` | Yes | — | Actor username |
| `updated_by` | `VARCHAR(120)` | Yes | — | Actor username |
| `removed_by` | `VARCHAR(120)` | Yes | — | Actor username at trash time |
| `version` | `BIGINT` | No | `0` | `@Version` optimistic lock |

**Keys and constraints**

- **PK:** `id`. **Unique:** `category_code`; constraint name _Not documented in source._
- **NOT NULL:** `id`, `category_code`, `name`, `version`.
- **FK:** none outgoing. `project_categories.category_id` points here.
- **CHECK:** none.

**Indexes**

From `@Table(indexes = ...)`: `idx_category_code` (`category_code`), `idx_category_removed_at`
(`removed_at`).

From `CategorySearchIndexInitializer.ensureSearchIndexes()`, verbatim:

```sql
CREATE INDEX IF NOT EXISTS idx_categories_name_lower_trgm
ON categories USING GIN (LOWER(name) gin_trgm_ops)
```

```sql
CREATE INDEX IF NOT EXISTS idx_categories_description_lower_trgm
ON categories USING GIN (LOWER(description) gin_trgm_ops)
```

There is **no** `text_pattern_ops` btree on `name` — unlike the media tables, a 1–2 character prefix
search over category names cannot use an index.

**Relationships**

| Field | Kind | Target | Fetch | Cascade / orphanRemoval | Table |
|---|---|---|---|---|---|
| `keywords` | `@ElementCollection` | `String` | `LAZY` | implicit (owned) | `category_keywords` |

`Category` holds no reference back to `Project`. The many-to-many is owned by `Project` and there is
no `mappedBy` side, so a category cannot navigate to its projects in JPA — query
`project_categories` directly, or use `ProjectRepository`, which does exactly that:

```sql
SELECT CASE WHEN COUNT(p) > 0 THEN true ELSE false END
  FROM Project p JOIN p.categories c
 WHERE c = :category AND p.removedAt IS NULL
```

**Notes**

- The same initializer also runs
  `ALTER TABLE category_audit_logs DROP CONSTRAINT IF EXISTS category_audit_logs_action_check`,
  because `ddl-auto=update` never refreshes a Hibernate-generated enum CHECK. That statement targets
  the audit-log table, not `categories`.
- A category that is still referenced by `project_categories` can be trashed
  (`removed_at` set) without any FK complaint — trashing is an UPDATE. Only permanent deletion
  interacts with the FK.

## `category_keywords`

**Entity:** `ak.dev.khi_archive_platform.platform.model.category.Category` (field `keywords`)

| Column | Type | Null | Default | Description |
|---|---|---|---|---|
| `category_id` | `BIGINT` | No | — | **FK → `categories.id`.** Owner |
| `keyword` | `TEXT` | Yes | — | Alternative name / keyword for the category |

**Keys and constraints** — No PK (bag mapping). FK `category_id` → `categories.id`; constraint name
_Not documented in source._

**Indexes** — created by `CategorySearchIndexInitializer`, verbatim:

```sql
CREATE INDEX IF NOT EXISTS idx_category_keywords_lower_trgm
ON category_keywords USING GIN (LOWER(keyword) gin_trgm_ops)
```

No btree on `category_id` and no `text_pattern_ops` index.

**Relationships** — `@ElementCollection(fetch = LAZY)`, `@CollectionTable(name = "category_keywords", joinColumns = @JoinColumn(name = "category_id"))`, `@Column(name = "keyword", columnDefinition = "TEXT")`.

**Notes**

- Purpose per the entity Javadoc: "Keywords / alternative names for this category. Used to prevent
  duplicate categories with similar meanings." Dedupe logic lives in `CategoryService`, not in a
  constraint.
- This is the sixth table in the keyword vocabulary union declared by `KeywordVocabularyService` —
  the only non-media one: `new CollectionTableRef("category_keywords", "keyword", "category_id", "categories")`.
- Without a btree on `category_id`, resolving one category's keywords is a scan. `CategoryRepository.searchByText`
  runs three correlated subqueries against this table per candidate row:

```sql
OR EXISTS (
      SELECT 1 FROM category_keywords k
       WHERE k.category_id = c.id
         AND similarity(LOWER(k.keyword), LOWER(:q)) > :threshold
  )
```

  Fine at vocabulary scale, not a pattern to copy for a growing table.
- `CategoryRepository.findAllActiveWithKeywords()` uses `LEFT JOIN FETCH c.keywords` specifically to
  avoid N+1 selects against this table when listing categories.

---

## `person`

**Entity:** `ak.dev.khi_archive_platform.platform.model.person.Person`

Table name is `person`, singular. It is the only content table not pluralized — do not write
`persons`.

| Column | Type | Null | Default | Description |
|---|---|---|---|---|
| `id` | `BIGINT` identity | No | identity | **PK** |
| `person_code` | `VARCHAR(50)` | No | — | Business key, `UNIQUE`. e.g. `HZI`, `AMA` |
| `media_portrait` | `VARCHAR(255)` | Yes | — | S3 object URL of the portrait |
| `full_name` | `TEXT` | No | — | Full legal / historical name |
| `nickname` | `TEXT` | Yes | — | Nickname / pen name |
| `romanized_name` | `VARCHAR(255)` | Yes | — | Latin-script name |
| `gender` | ordinal enum — see Notes | Yes | — | `Gender`: `MALE`, `FEMALE` |
| `region` | `VARCHAR(255)` | Yes | — | Region |
| `date_of_birth` | `DATE` | Yes | — | Birth date |
| `date_of_birth_precision` | `VARCHAR(20)` | Yes | — | `DatePrecision`, `@Enumerated(STRING)`: `FULL`, `MONTH_ONLY`, `YEAR_ONLY` |
| `place_of_birth` | `VARCHAR(255)` | Yes | — | Birth place |
| `date_of_death` | `DATE` | Yes | — | Death date |
| `date_of_death_precision` | `VARCHAR(20)` | Yes | — | `DatePrecision`, `@Enumerated(STRING)` |
| `place_of_death` | `VARCHAR(255)` | Yes | — | Death place |
| `description` | `TEXT` | Yes | — | Biography |
| `tag` | `TEXT` | Yes | — | Tags as **one** text column, not a collection table |
| `keywords` | `TEXT` | Yes | — | Keywords as **one** text column, not a collection table |
| `note` | `TEXT` | Yes | — | Free note |
| `created_at` | `TIMESTAMP(6) WITH TIME ZONE` | Yes | — | Row creation |
| `updated_at` | `TIMESTAMP(6) WITH TIME ZONE` | Yes | — | Last write |
| `removed_at` | `TIMESTAMP(6) WITH TIME ZONE` | Yes | — | Soft-trash marker |
| `created_by` | `VARCHAR(120)` | Yes | — | Actor username |
| `updated_by` | `VARCHAR(120)` | Yes | — | Actor username |
| `removed_by` | `VARCHAR(120)` | Yes | — | Actor username at trash time |
| `version` | `BIGINT` | No | `0` | `@Version` optimistic lock |

**Keys and constraints**

- **PK:** `id`. **Unique:** `person_code`; constraint name _Not documented in source._
- **NOT NULL:** `id`, `person_code`, `full_name`, `version`.
- **FK:** none outgoing. `projects.person_id` points here.
- **CHECK:** Hibernate generates enum CHECK constraints at table-create time for
  `date_of_birth_precision`, `date_of_death_precision` and `gender`. No initializer drops or
  re-syncs them, unlike the `*_audit_logs.action` constraints. The exact generated constraint text is
  _Not documented in source._ — inspect the live database before adding a value to `Gender` or
  `DatePrecision`, because `ddl-auto=update` will not widen an existing CHECK.

**Indexes**

From `@Table(indexes = ...)`: `idx_person_code` (`person_code`), `idx_person_region` (`region`),
`idx_person_removed_at` (`removed_at`).

From `PersonSearchIndexInitializer.ensureSearchIndexes()`, all GIN trigram, verbatim shape:

```sql
CREATE INDEX IF NOT EXISTS idx_person_full_name_lower_trgm
ON person USING GIN (LOWER(full_name) gin_trgm_ops)
```

| Index | Column |
|---|---|
| `idx_person_full_name_lower_trgm` | `full_name` |
| `idx_person_nickname_lower_trgm` | `nickname` |
| `idx_person_romanized_name_lower_trgm` | `romanized_name` |
| `idx_person_description_lower_trgm` | `description` |
| `idx_person_tag_lower_trgm` | `tag` |
| `idx_person_keywords_lower_trgm` | `keywords` |
| `idx_person_region_lower_trgm` | `region` |
| `idx_person_place_of_birth_lower_trgm` | `place_of_birth` |
| `idx_person_place_of_death_lower_trgm` | `place_of_death` |

Note `region` carries both a plain btree (`idx_person_region`, from the entity) and a trigram index —
equality filters use the first, substring search the second.

**Relationships**

| Field | Kind | Target | Fetch | Cascade / orphanRemoval | Table |
|---|---|---|---|---|---|
| `personType` | `@ElementCollection` | `String` | **`EAGER`** | implicit (owned) | `person_person_type` |

`Person` has no mapping to `Project`; the FK lives on `projects.person_id` and the association is
unidirectional from `Project`.

**Notes**

- **`gender` is stored as a number, not text.** The field has no `@Enumerated`, so JPA's default
  `EnumType.ORDINAL` applies: `MALE` = 0, `FEMALE` = 1. The `length = 50` on the `@Column` has no
  effect on a numeric column. The two `DatePrecision` columns *do* carry `@Enumerated(EnumType.STRING)`
  and are text. A query that reads `WHERE gender = 'MALE'` will fail; use the ordinal. The exact SQL
  integer type Hibernate picks for an ordinal enum is version-dependent and is
  _Not documented in source._ — confirm against the live column type.
- **Reordering the `Gender` enum silently rewrites history.** Because the ordinal is what is stored,
  inserting a value before `FEMALE` reinterprets every existing row. Only append.
- `personType` is the single `EAGER` collection in this whole schema. Every `Person` load issues a
  second query (or a join) for `person_person_type`, whether or not you need it.
- `tag` and `keywords` on `person` are single `TEXT` columns holding whatever the service wrote.
  They are **not** part of the tag/keyword collection-table union used by the autocomplete endpoints,
  and they are not canonicalized by `Tags`/`Keywords`.
- `media_portrait` is `VARCHAR(255)` while every other S3 URL column is 500 or 1000 — the tightest
  URL column in the schema.
- `PersonSearchIndexInitializer` also drops `person_audit_logs_action_check`; that statement targets
  the audit-log table, not `person`.

## `person_person_type`

**Entity:** `ak.dev.khi_archive_platform.platform.model.person.Person` (field `personType`)

| Column | Type | Null | Default | Description |
|---|---|---|---|---|
| `person_id` | `BIGINT` | No | — | **FK → `person.id`.** Owner |
| `person_type` | `VARCHAR(255)` | Yes | — | One role label (`length = 255`, no `columnDefinition`) |

**Keys and constraints** — No PK (bag mapping). FK `person_id` → `person.id`; constraint name
_Not documented in source._

**Indexes** — created by `PersonSearchIndexInitializer`, verbatim:

```sql
CREATE INDEX IF NOT EXISTS idx_person_person_type_lower_trgm
ON person_person_type USING GIN (LOWER(person_type) gin_trgm_ops)
```

No btree on `person_id`.

**Relationships** — `@ElementCollection(fetch = FetchType.EAGER)`, `@CollectionTable(name = "person_person_type", joinColumns = @JoinColumn(name = "person_id"))`, `@Column(name = "person_type", length = 255)`.

**Notes**

- The only collection column in this schema typed `VARCHAR(255)` rather than `TEXT`. A role label
  longer than 255 characters fails on insert.
- The `Person.personType` field is declared without `@Builder.Default`, so a `Person` built through
  the Lombok builder without setting it gets `null`, not an empty list. Every other collection in
  these entities defaults to `new ArrayList<>()`.
- Fetching is `EAGER`; the person list endpoints therefore always materialize this table.

---

## `projects`

**Entity:** `ak.dev.khi_archive_platform.platform.model.project.Project`

| Column | Type | Null | Default | Description |
|---|---|---|---|---|
| `id` | `BIGINT` identity | No | identity | **PK.** Target of `audios/videos/images/texts.project_id` |
| `project_code` | `VARCHAR(200)` | No | — | Business key, `UNIQUE`. `PERSONCODE-PROJ-######` or `PROJECTNAME-PROJ-######` |
| `project_name` | `TEXT` | No | — | Display name |
| `person_id` | `BIGINT` | Yes | — | **FK → `person.id`.** Nullable — a project need not belong to a person |
| `description` | `TEXT` | Yes | — | Description |
| `is_visible_to_public` | `BOOLEAN NOT NULL DEFAULT TRUE` | No | `TRUE` | Project-level guest visibility |
| `created_at` | `TIMESTAMP(6) WITH TIME ZONE` | Yes | — | Row creation |
| `updated_at` | `TIMESTAMP(6) WITH TIME ZONE` | Yes | — | Last write |
| `removed_at` | `TIMESTAMP(6) WITH TIME ZONE` | Yes | — | Soft-trash marker |
| `created_by` | `VARCHAR(120)` | Yes | — | Actor username |
| `updated_by` | `VARCHAR(120)` | Yes | — | Actor username |
| `removed_by` | `VARCHAR(120)` | Yes | — | Actor username at trash time |
| `version` | `BIGINT` | No | `0` | `@Version` optimistic lock |

**Keys and constraints**

- **PK:** `id`. **Unique:** `project_code`; constraint name _Not documented in source._
- **NOT NULL:** `id`, `project_code`, `project_name`, `is_visible_to_public`, `version`.
- **FK:** `person_id` → `person.id`, nullable, no on-delete behavior declared.
- Incoming FKs: `audios.project_id`, `videos.project_id`, `images.project_id`, `texts.project_id`
  (all `NOT NULL`), plus `project_categories.project_id`.
- **CHECK:** none — no enum-typed column.

**Indexes**

From `@Table(indexes = ...)` only:

| Index | Columns |
|---|---|
| `idx_project_code` | `project_code` |
| `idx_project_person_id` | `person_id` |
| `idx_project_removed_at` | `removed_at` |

No initializer creates trigram or pattern indexes on `projects` or on its two collection tables.
That matters because `ProjectRepository.searchByText` is a native query that calls
`similarity(LOWER(p.project_name), LOWER(:q))` and `LIKE LOWER(CONCAT('%', :q, '%'))` against
`projects`, `project_tags` and `project_keywords`:

```sql
SELECT p.* FROM projects p
WHERE p.removed_at IS NULL
  AND (
        LOWER(p.project_name) LIKE LOWER(CONCAT('%', :q, '%'))
     OR LOWER(p.project_code) LIKE LOWER(CONCAT('%', :q, '%'))
     OR LOWER(COALESCE(p.description, '')) LIKE LOWER(CONCAT('%', :q, '%'))
     ...
     OR similarity(LOWER(p.project_name), LOWER(:q)) > :threshold
  )
```

The `similarity()` function comes from `pg_trgm`, which the media/category/person initializers
install — but no GIN trigram index backs any of these columns, so every one of those predicates is a
sequential scan. This is the one core content table with no `pg_trgm` index support at all.

**Relationships**

| Field | Kind | Target | Fetch | Cascade / orphanRemoval | Table |
|---|---|---|---|---|---|
| `person` | `@ManyToOne` | `Person` | `LAZY` | none | FK column `person_id` |
| `categories` | `@ManyToMany` | `Category` | `LAZY` | none / no orphanRemoval | Join table `project_categories` |
| `tags` | `@ElementCollection` | `String` | `LAZY` | implicit (owned) | `project_tags` |
| `keywords` | `@ElementCollection` | `String` | `LAZY` | implicit (owned) | `project_keywords` |

`Project` declares **no** `@OneToMany` to `Audio`, `Video`, `Image` or `Text`. Every project→media
traversal is a repository query filtered on `project_id`, which is why the cascade operations in
`ProjectService` are explicit per-type calls rather than JPA cascades.

**Notes**

- Trashing a project cascades to its media: `softTrashByProject` is called on all four media
  repositories, each stamping `removed_at`/`removed_by` and bumping `version` on rows that were
  active. Restore calls `restoreByProject`, which clears `removed_at`/`removed_by` on **every**
  trashed row under the project — including ones trashed individually beforehand.
- Permanent deletion is admin-only (authority `project:delete`), requires the project to be in the
  trash, deletes the S3 objects for each child media row, emits per-row cascade audits, then runs
  `deleteAll(...)` on the four media repositories before `projectRepository.delete(project)`. The
  database performs no cascade of its own.
- Visibility cascade is opt-in per update call. When it runs, only rows whose value actually differs
  are touched (`AND (a.isPublic IS NULL OR a.isPublic <> :isPublic)`), so the returned counts are
  "rows changed", not "rows in project".
- `person_id` is nullable by design: an "untitled" project derives its code from the project name
  instead of a person code.

## `project_categories`

**Entity:** `ak.dev.khi_archive_platform.platform.model.project.Project` (field `categories`)

Join table for the `Project` ↔ `Category` many-to-many.

| Column | Type | Null | Default | Description |
|---|---|---|---|---|
| `project_id` | `BIGINT` | No | — | **FK → `projects.id`.** Owning side |
| `category_id` | `BIGINT` | No | — | **FK → `categories.id`.** Inverse side |

**Keys and constraints**

- **PK:** none declared — the mapping is a `List` with no `@OrderColumn`, i.e. a bag.
- **FKs:** `project_id` → `projects.id`, `category_id` → `categories.id`. No on-delete behavior
  declared; constraint names are Hibernate-generated — _Not documented in source._
- **Unique:** none. Nothing at the database level prevents the same `(project_id, category_id)` pair
  appearing twice.

**Indexes** — none. No `@Index` on the `@JoinTable` and no initializer creates one. Both FK columns
are unindexed, so "which projects use category X" scans this table.

**Relationships** — declared on `Project`:

```java
@Builder.Default
@ManyToMany(fetch = FetchType.LAZY)
@JoinTable(
        name = "project_categories",
        joinColumns = @JoinColumn(name = "project_id"),
        inverseJoinColumns = @JoinColumn(name = "category_id")
)
private List<Category> categories = new ArrayList<>();
```

Fetch `LAZY`, no cascade, no `orphanRemoval`. `Category` has no `mappedBy` side, so this association
is navigable only from `Project`.

**Notes**

- "At least one category is required on creation" per the field's Javadoc — enforced in the service,
  not by a constraint. A project row with zero rows here is legal SQL.
- The table does not carry `removed_at`. Rows for a trashed project remain, so any category-usage
  query must join `projects` and filter `projects.removed_at IS NULL`.
- Because the collection is a bag, Hibernate replaces all rows for a project on every category edit
  (`DELETE` then `INSERT`), not a differential update.

## `project_tags`

**Entity:** `ak.dev.khi_archive_platform.platform.model.project.Project` (field `tags`)

| Column | Type | Null | Default | Description |
|---|---|---|---|---|
| `project_id` | `BIGINT` | No | — | **FK → `projects.id`.** Owner |
| `tag` | `TEXT` | Yes | — | One canonicalized tag, ≤ 64 chars |

**Keys and constraints** — No PK (bag mapping). FK `project_id` → `projects.id`; constraint name
_Not documented in source._

**Indexes** — **None.** No initializer touches `project_tags`, unlike the four media `*_tags`
tables which each get trigram, pattern and FK indexes.

**Relationships** — `@ElementCollection(fetch = LAZY)`, `@CollectionTable(name = "project_tags", joinColumns = @JoinColumn(name = "project_id"))`, `@Column(name = "tag", columnDefinition = "TEXT")`.

**Notes**

- One of the five tables in the tag vocabulary union declared by `TagVocabularyService`
  (`audio_tags`, `video_tags`, `image_tags`, `text_tags`, `project_tags`), and the only unindexed
  member — it is the slow leg of `GET /api/tags/suggest`.
- `ProjectRepository.searchByText` runs both `LOWER(t.tag) LIKE '%…%'` and
  `similarity(LOWER(t.tag), LOWER(:q))` correlated subqueries against this table with no index on
  `project_id` or `tag`.

## `project_keywords`

**Entity:** `ak.dev.khi_archive_platform.platform.model.project.Project` (field `keywords`)

| Column | Type | Null | Default | Description |
|---|---|---|---|---|
| `project_id` | `BIGINT` | No | — | **FK → `projects.id`.** Owner |
| `keyword` | `TEXT` | Yes | — | One canonicalized keyword, ≤ 200 chars |

**Keys and constraints** — No PK (bag mapping). FK `project_id` → `projects.id`; constraint name
_Not documented in source._

**Indexes** — **None.** No initializer touches `project_keywords`.

**Relationships** — `@ElementCollection(fetch = LAZY)`, `@CollectionTable(name = "project_keywords", joinColumns = @JoinColumn(name = "project_id"))`, `@Column(name = "keyword", columnDefinition = "TEXT")`.

**Notes**

- One of the six tables in the keyword vocabulary union declared by `KeywordVocabularyService`
  (the five media/project keyword tables plus `category_keywords`). Unindexed, same caveat as
  `project_tags`.
- Also hit by `ProjectRepository.searchByText` through `LIKE` and `similarity()` subqueries with no
  supporting index.

---

## `khi_logo`

**Entity:** `ak.dev.khi_archive_platform.platform.model.khilogo.KhiLogo`

The institutional logo pointer. This table follows none of the shared conventions above — no
business key, no visibility flag, no trash columns, no `version`, no audit actor columns.

| Column | Type | Null | Default | Description |
|---|---|---|---|---|
| `id` | `BIGINT` identity | No | identity | **PK.** Addressed directly by the API, unlike every other content table |
| `image_url` | `VARCHAR(500)` | No | — | S3 object URL of the logo image |
| `created_at` | `TIMESTAMP(6) WITH TIME ZONE` | Yes | — | Row creation |
| `updated_at` | `TIMESTAMP(6) WITH TIME ZONE` | Yes | — | Last write |

**Keys and constraints**

- **PK:** `id`.
- **NOT NULL:** `id`, `image_url`.
- **Unique:** none. Nothing constrains the table to a single row.
- **FK:** none, in either direction.
- **CHECK:** none.

**Indexes** — none. `@Table(name = "khi_logo")` declares no `indexes`, and no initializer creates
any. Only the PK index exists.

**Relationships** — none. `KhiLogo` declares no association of any kind.

**Notes**

- **Hard delete only.** `KhiLogoService.delete(id)` calls `khiLogoRepository.delete(logo)` and then
  removes the S3 object. There is no trash and no restore for this table.
- On update the service uploads the new object first, saves the row, and only then deletes the old
  object — and only when `S3Service.isOurS3Url(oldImageUrl)` is true. A row whose `image_url` points
  outside the configured bucket keeps its old object in place.
- `@PrePersist` here sets `created_at` and `updated_at` unconditionally, unlike the other entities
  which only fill them when still null. A caller-supplied `createdAt` is overwritten.
- The table can legitimately hold several rows; "the current logo" is a decision made by the caller,
  not by a constraint.

---

## Operational notes for all of these tables

- **Index creation is best-effort and non-transactional.** All three initializers run on
  `ApplicationReadyEvent` and only `log.warn` on failure. `MediaSearchIndexInitializer` wraps every
  statement in its own `try/catch`, so one failing index does not stop the rest;
  `CategorySearchIndexInitializer` and `PersonSearchIndexInitializer` wrap their whole index block in
  a single `try/catch`, so the first failure skips every remaining index in that initializer. A
  startup that logs `Failed to create trigram index ...` still serves traffic — with sequential
  scans. Check the logs after any schema change.
- **`CREATE EXTENSION IF NOT EXISTS pg_trgm` runs three times** (media, category and person
  initializers). It is idempotent, but the database role must be allowed to create extensions or all
  trigram indexes are skipped: `MediaSearchIndexInitializer` returns early from
  `ensureSearchIndexes()` if the extension call fails.
- **Every trigram and pattern index is on `LOWER(column)`.** A predicate written as
  `column ILIKE :q` will not match the index expression; write `LOWER(column) LIKE LOWER(:q)`.
- **Seeding.** `SeedDataLoader` (a `CommandLineRunner`, `@ConditionalOnProperty(name = "app.seed.load", havingValue = "true")`)
  populates `categories`, `person`, `projects`, `audios`, `videos`, `texts` and `images` from
  `${app.seed.dir}` JSON files. In `application.yaml` the flag is `${APP_SEED_LOAD:true}`, so it is
  on unless `APP_SEED_LOAD` says otherwise.
- **Batching — declared but not in effect.** `application.yaml` sets
  `spring.jpa.jdbc.batch_size: 100`, `order_inserts: true`, `order_updates: true` and
  `spring.jpa.hibernate.properties.hibernate.default_batch_fetch_size: 1000`. All four are written
  at property paths Spring Boot does not bind, so Hibernate never receives them (verified — see
  [Indexes and performance](./indexes-and-performance.md#eight-hibernate-keys-are-inert-verified)).
  Bulk media ingest therefore runs one round trip per row, and lazy collections load one `SELECT`
  per parent. Once the nesting is corrected, the usual caveat returns: a per-row `saveAndFlush`
  loop defeats JDBC batching.

## Related

- [Database docs index](./README.md)
- [Project endpoints](../content/project.md) — trash, restore and the visibility cascade as an API
- [Person endpoints](../content/person.md)
- [Category endpoints](../content/category.md)
- [Items endpoint](../content/items.md) — the merged view over the four media tables
- [Tags and keywords](../content/tags-and-keywords.md) — the autocomplete unions over the tables above
- [KHI logo endpoints](../content/khi-logo.md)
