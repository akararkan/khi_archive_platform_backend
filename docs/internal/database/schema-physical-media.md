# Schema — Physical Media Inventory Tables

> **Audience:** Backend / DBA · **Source:** `platform/model/physicalmedia/`, `platform/enums/DigitizationStatus.java`, `platform/config/PhysicalMedia*Initializer.java`, `platform/config/PhysicalMediaTypeSeeder.java`

The physical-media inventory records one row per physical artefact held by the archive — a
cassette, a reel, a VHS tape, a disc. Its table is a 1:1 mirror of the team's source
spreadsheet (`All Final Archive Lists.xlsx → Sheet1`, 29 columns) so the Apache POI importer can
round-trip the sheet without losing context, wrapped in the platform's usual business-key,
soft-delete, optimistic-lock and audit envelope. A second table holds the catalog of allowed
media types together with the nine technical capture defaults that travel with each type.

Both tables are created by Hibernate under `ddl-auto=update`. Three boot-time beans in
`platform/config/` finish the job that `update` cannot do: `PhysicalMediaTypeSeeder` seeds the
type catalog, `PhysicalMediaDigitizationConstraintInitializer` re-syncs the `digitization` CHECK
constraint, and `PhysicalMediaSizeColumnMigrationInitializer` backfills a renamed column. (A
fourth `PhysicalMedia*` bean in the same package, `PhysicalMediaAuditActionConstraintInitializer`,
belongs to `physical_media_audit_logs` and is covered with the audit tables.)

## Tables at a glance

| Table | Java entity | Purpose | Rows grow with |
|---|---|---|---|
| `physical_media` | `ak.dev.khi_archive_platform.platform.model.physicalmedia.PhysicalMedia` | One inventory row per physical artefact; mirrors all 29 spreadsheet columns | Every artefact catalogued by hand or ingested from an `.xlsx` import (~4,400 rows in the first sheet import) |
| `physical_media_types` | `ak.dev.khi_archive_platform.platform.model.physicalmedia.PhysicalMediaType` | Catalog of allowed media-type names plus per-type capture defaults | Roughly one new row a year, plus auto-created rows when an import carries an unknown type |

Related but documented elsewhere: `physical_media_audit_logs`
(`platform/model/physicalmedia/PhysicalMediaAuditLog.java`) carries the action trail for both
tables and is covered with the other `*_audit_logs` tables — see [Related](#related).

There are **no** `@ElementCollection`, `@CollectionTable` or `@JoinTable` mappings anywhere in
the physical-media package, so these two tables plus `physical_media_audit_logs` are every table
the package declares — no side tables, no join tables. Neither entity declares a `@ManyToOne`,
`@OneToMany` or `@ManyToMany` association.

---

## `physical_media`

**Entity:** `ak.dev.khi_archive_platform.platform.model.physicalmedia.PhysicalMedia`

Table name is explicit: `@Table(name = "physical_media")`. Every persisted field except the `id`
carries an explicit `@Column(name = ...)`; `id` is the one column whose name comes from
Hibernate's default naming strategy.

| Column | Type | Null | Default | Description |
|---|---|---|---|---|
| `id` | `bigint` | no | identity | **PK**. `@GeneratedValue(strategy = IDENTITY)` — Postgres identity column. |
| `pm_code` | `varchar(60)` | no | — | Internal unique business key, format `PM_NNNNNN` (e.g. `PM_000001`). `unique = true`; every API path addresses a row by this code, never by `id`. Minted by `PhysicalMediaService.generateCode()` as `count() + 1` zero-padded to six digits, under a `CodeGenLock` advisory lock. |
| `row_number` | `integer` | yes | — | Sheet column `No.` — the row ordinal exactly as recorded in the spreadsheet. Carried through to `PhysicalMediaResponseDTO`, sortable (`sortBy=rowNumber` / `row` / `no`) and filterable (`rowNumberMin` / `rowNumberMax`); nothing in the business logic derives anything from it. |
| `inventory_number` | `integer` | yes | — | Sheet column `Number` — a **per-media-type contiguous counter** (Audio Cassette 1..1893, VHS Cassette 1..55, … — see [the per-type ranges](#the-source-sheets-per-type-number-ranges)). Import preserves the sheet's own value when the cell carries one and otherwise falls through to the same per-type `MAX + 1`; manual create always server-assigns `MAX(inventory_number) + 1` for that type and ignores any client value. |
| `physical_media_type` | `varchar(200)` | yes | — | Sheet column `Physical Media Type`, e.g. `Audio Cassette`, `VHS Cassette`. **Logical FK to `physical_media_types.name` — matched by string value, with no database FK constraint.** |
| `media_category` | `varchar(200)` | yes | — | Sheet column `Media Category` — high-level bucket (Audio, Video, …). Free text; no catalog table backs it. |
| `title` | `text` | yes | — | Sheet column `Title` — display title, Sorani Kurdish or free text. `columnDefinition = "TEXT"`. |
| `size_gb` | `varchar(200)` | yes | — | Sheet column `Size GB` — **digital** file size, stored as free text because the sheet is inconsistent (`4.7`, `4.7 GB`, `700 MB`). Repurposed from the retired `sub_type` column; see [Column migration](#column-migration-physicalmediasizecolumnmigrationinitializer). Distinct from `physical_size`. |
| `physical_label` | `varchar(200)` | yes | — | Sheet column `Physical Label` — the sticker/label on the artefact itself. **Not globally unique**, and only meaningful within a media type. |
| `physical_size` | `varchar(200)` | yes | — | Sheet column `Physical Size` — **material** size of the artefact (big / medium / normal / small). Backed by the `physical_size` column, migrated once from the legacy `size` column. Distinct from `size_gb`. |
| `content` | `text` | yes | — | Sheet column `Content` — free-text description of what is on the artefact. |
| `archive_dep_note` | `text` | yes | — | Sheet column `Archive Dep Note` — note from the archive department. |
| `digitization` | `varchar(20)` | yes | — | Sheet column `Digitization`. `@Enumerated(EnumType.STRING)` over `DigitizationStatus`, so the stored value is the name (`NOT_DIGITIZED` / `DIGITIZED` / `DUPLICATED`), not the sheet's `0`/`1`/`2`. A blank sheet cell stores `NULL`. Guarded by a CHECK constraint — see [Keys and constraints](#keys-and-constraints). |
| `owner` | `text` | yes | — | Sheet column `Owner` — owner / producer attribution. |
| `year` | `integer` | yes | — | Sheet column `Year` — the year printed on the artefact label. |
| `duration_min` | `integer` | yes | — | Sheet column `Duration Min` — runtime in minutes. |
| `track_numbers` | `integer` | yes | — | Sheet column `Track Numbers` — count of tracks (a count, not a list). |
| `track_name` | `text` | yes | — | Sheet column `Track Name` — free-text track listing. |
| `extension` | `varchar(50)` | yes | — | Sheet column `Extension` — file extension produced on capture (`wav`, `mp4`, `avi`, …). Autofilled from the type catalog. |
| `bit_or_color_depth` | `varchar(100)` | yes | — | Sheet column `Bit Depth / Color Depth`. Autofilled from the type catalog. |
| `sample_or_frame_rate` | `varchar(100)` | yes | — | Sheet column `Sample Rate kHz / Frame Rate fps`. Autofilled from the type catalog. |
| `channels_or_resolution` | `varchar(100)` | yes | — | Sheet column `Channels / Resolution` (`Stereo`, `720X576`, …). Autofilled from the type catalog. |
| `playback_model` | `text` | yes | — | Sheet column `Playback Model` — the playback hardware used during capture. Autofilled from the type catalog. |
| `capture_interface` | `text` | yes | — | Sheet column `Capture Interface` — audio/video interface sitting in front of the playback deck. Autofilled from the type catalog. |
| `signal_interface` | `text` | yes | — | Sheet column `Signal Interface (Cable Type)`. Autofilled from the type catalog. |
| `ingest_software` | `text` | yes | — | Sheet column `Ingest Software` — application used during ingest. Autofilled from the type catalog. |
| `format_codec` | `varchar(200)` | yes | — | Sheet column `Format / Codec`. Autofilled from the type catalog. |
| `digitize_date` | `date` | yes | — | Sheet column `Digitize Date`. `LocalDate`, so a bare calendar date — the sheet only carries day precision and the importer must not invent a time zone. |
| `tags` | `text` | yes | — | Sheet column `Tags` — free-text tag list, comma/slash-separated as found in the sheet. **Unrelated to the platform tag system**; this is not normalized into the `*_tags` tables. |
| `need_to_clear` | `boolean` | yes | — | Sheet column `Need to Clear` — `0`/`1` in Excel, decoded to a boolean on import. |
| `capture_dep_note` | `text` | yes | — | Sheet column `Capture Dep Note` — note from the capture (digitization) department. |
| `created_at` | `timestamp(6) with time zone` | yes | — | Set in `@PrePersist` when null. `Instant`. |
| `updated_at` | `timestamp(6) with time zone` | yes | — | Set in `@PrePersist` when null, and overwritten unconditionally in `@PreUpdate`. |
| `removed_at` | `timestamp(6) with time zone` | yes | — | **Soft-delete marker.** `NULL` = active, non-null = in trash. Same pattern as the audio/video entities. |
| `created_by` | `varchar(120)` | yes | — | Username of the actor who created the row (`system` when unauthenticated, the importer's actor on import). |
| `updated_by` | `varchar(120)` | yes | — | Username of the last actor to update the row. |
| `removed_by` | `varchar(120)` | yes | — | Username of the actor who trashed the row. |
| `source` | `varchar(20)` | yes | `MANUAL` (via `@PrePersist`) | How the row first entered the system: `MANUAL` or `IMPORT`. Plain string, **not** an `@Enumerated` column — there is no CHECK constraint restricting it. |
| `version` | `bigint` | no | `0` | `@Version` optimistic-lock counter, with `@org.hibernate.annotations.ColumnDefault("0")`. Concurrent updates raise `ObjectOptimisticLockingFailureException` → HTTP `409`. |

Columns `row_number` through `capture_dep_note` are exactly the 29 spreadsheet columns, in sheet
order.

The `Sheet column` labels above are the entity's own javadoc names. The importer matches real
header cells against `PhysicalMediaExcelImportService.HEADER_BINDINGS`, which additionally carries
Kurdish-prefixed aliases (`جۆری بابەت(Media Category)`, `تاگ Tags`, …) and spells four headers
without spaces around the separator — `Sample Rate kHz/Frame Rate fps`, `Channels/Resolution`,
`Signal Interface(Cable Type)` and `Format/Codec`. Check that map, not this table, before renaming
a column in the sheet.

### Keys and constraints

| Kind | Name | Definition |
|---|---|---|
| Primary key | Hibernate default (`physical_media_pkey`) | `(id)`, identity-generated |
| Unique | `uk_pm_code` | `(pm_code)` — declared as `@UniqueConstraint(name = "uk_pm_code", columnNames = "pm_code")` **and** as `unique = true` on the field |
| Not null | — | `id` (identity PK), `pm_code`, `version` |
| CHECK | `physical_media_digitization_check` | `CHECK (digitization IS NULL OR digitization IN ('NOT_DIGITIZED','DIGITIZED','DUPLICATED'))` — created by `PhysicalMediaDigitizationConstraintInitializer`, not by Hibernate |

**No foreign keys.** `physical_media_type` points at `physical_media_types.name` by string value
only; there is no `REFERENCES` clause, and therefore no on-delete behavior to declare. Referential
integrity is enforced in application code instead:

- On manual create, `PhysicalMediaService.validateRequiredOnCreate` rejects a type that is not in
  the catalog (`"not in the type catalog — add it via /api/physical-media/types first"`).
- On import, `PhysicalMediaService.insertFromImport` calls
  `PhysicalMediaTypeService.ensureExists(...)`, which silently creates the missing catalog row.
- On type delete, `PhysicalMediaTypeService.delete` counts rows whose `physical_media_type`
  equals the type name and refuses the delete while any exist — "there's no FK so we'd silently
  orphan them".

Renaming a catalog row's `name` does **not** rewrite `physical_media.physical_media_type`. The
rename succeeds and the existing inventory rows keep pointing at the old string.

Apart from the identity PK, only two columns are `NOT NULL` at the database level
(`pm_code` and `version`). Everything the archive team types is
nullable on purpose: the importer's stance is that a half-empty row still belongs in the
database, and a human cleans it up later.

### Indexes

All seven are declared on the entity's `@Table(indexes = ...)` and are therefore created by
**Hibernate** at schema update time. No initializer bean creates an index on this table —
`AuditLogIndexInitializer` touches `physical_media_audit_logs`, not `physical_media`.

| Index | Columns | Created by | Why it exists |
|---|---|---|---|
| `idx_pm_code` | `pm_code` | Hibernate (`@Index`) | Code lookups (`findByPmCode`, `findByPmCodeAndRemovedAtIsNull`). Functionally redundant with `uk_pm_code`, which already backs a b-tree index. |
| `idx_pm_physical_label` | `physical_label` | Hibernate (`@Index`) | Label search and the natural-key lookup pattern. |
| `idx_pm_media_type` | `physical_media_type` | Hibernate (`@Index`) | Type facet/filter, and `MAX(inventory_number)` per type. |
| `idx_pm_media_category` | `media_category` | Hibernate (`@Index`) | Category facet/filter. |
| `idx_pm_digitization` | `digitization` | Hibernate (`@Index`) | Digitization-status filter and the inventory analytics rollups. |
| `idx_pm_need_to_clear` | `need_to_clear` | Hibernate (`@Index`) | "Needs clearing" worklist filter. Low-cardinality boolean — Postgres will often prefer a sequential scan anyway. |
| `idx_pm_removed_at` | `removed_at` | Hibernate (`@Index`) | Splits active from trashed on every list query (`removed_at IS NULL` / `IS NOT NULL`). |

There is **no** composite index on `(physical_media_type, physical_label)`.

### Relationships

None. `PhysicalMedia` declares no JPA association of any kind — no `@ManyToOne`, `@OneToMany`,
`@ManyToMany` or `@ElementCollection`. Its two logical links are:

| Logical link | Target | How it is expressed |
|---|---|---|
| Media type | `physical_media_types.name` | Plain `varchar(200)` value, application-enforced (see above) |
| Audit trail | `physical_media_audit_logs.physical_media_id` / `.physical_media_code` | Written by `PhysicalMediaAuditService`; the audit table denormalizes `physical_label`, `title` and `physical_media_type` so feed rows read without a join back |

### Notes

- **Soft delete is the only delete on the API surface.** `DELETE` sets `removed_at` /
  `removed_by`; the row stays. Purge is a separate admin action that requires the row to be
  trashed first. Every read query must therefore carry `removed_at IS NULL` — a query that
  forgets it will surface trashed inventory.
- **`pm_code` generation is count-based, not sequence-based.** `generateCode()` computes
  `repository.count() + 1` under a `CodeGenLock` advisory lock. The lock prevents concurrent
  collisions, but because it counts rows rather than reading a sequence, a **purge** frees a
  number that a later insert will re-use. Do not treat `pm_code` ordering as a chronology.
- **`inventory_number` counts trashed rows too.** `findMaxInventoryNumberByPhysicalMediaType`
  deliberately has no `removed_at` predicate, so the per-type sequence stays monotonic across the
  trash lifecycle.
- **`inventory_number` is not unique** at the database level, even per type. The advisory lock in
  `nextInventoryNumber` serializes concurrent manual creates of the same type, but an import that
  round-trips the sheet writes whatever the sheet contained, duplicates included.
- **`row_number`, `year`, `owner` and `content` are ordinary identifiers in PostgreSQL**, but
  `row_number` collides visually with the `row_number()` window function. Qualify it
  (`p.row_number`) in hand-written SQL.
- **Free-text search is a native `LIKE` query**, `PhysicalMediaRepository.searchByText`, over
  `pm_code`, `physical_label`, `physical_media_type`, `media_category`, `title`, `physical_size`,
  `content`, `owner`, `tags` and `track_name`. It is `LOWER(COALESCE(col,'')) LIKE '%…%'` with a
  `LIMIT`, so the leading wildcard means the indexes above cannot serve it. There is **no**
  `pg_trgm` index on this table.
- **Filtered list paths read the whole active set.** When any filter is present — or the request
  sorts on `digitization`, the one key that has no DB-mappable equivalent —
  `findAllByRemovedAtIsNullOrderByIdAsc()` / `findAllByRemovedAtIsNotNullOrderByIdAsc()` pull
  every row and filter in memory. An unfiltered request stays on the paged query
  (`findAllByRemovedAtIsNull(Pageable)`), including a sort-only request whose `sortBy` maps to a
  DB column and is pushed into the `Pageable`. Keep that in mind before adding columns that make
  the row wide.
- **`size_gb` and `physical_size` are two different measurements** with confusingly similar
  names. `size_gb` is the digital file size, `physical_size` is how big the object is. Both are
  free text.
- **`tags` here is not the platform tag system.** It is the sheet's raw string. It is not
  canonicalised, not split, and does not feed `GET /api/tags/suggest`.
- **`need_to_clear` is a plain boolean where `digitization` is an enum**, even though both arrive
  from the sheet as small integers. `Digitization` carries `0`/`1`/`2` and has room to grow, so it
  earned a named enum and the re-synced CHECK constraint above; `Need to Clear` only ever carries
  `0` or `1`, so a nullable boolean is the honest type and no constraint is needed. In both cases a
  blank cell stays `NULL` — "unknown" is a legal state, not a default of `false`, so a
  "needs clearing" worklist query must decide explicitly whether `NULL` belongs in it.

---

## `physical_media_types`

**Entity:** `ak.dev.khi_archive_platform.platform.model.physicalmedia.PhysicalMediaType`

Table name is explicit: `@Table(name = "physical_media_types")`. Every persisted field except the
`id` carries an explicit `@Column(name = ...)`, exactly as on `physical_media`.

The catalog exists instead of a Java enum because the team meets an unfamiliar format roughly
once a year; a new enum constant would mean a redeploy, whereas a catalog row is an admin screen
edit that takes effect the same day.

| Column | Type | Null | Default | Description |
|---|---|---|---|---|
| `id` | `bigint` | no | identity | **PK**. `@GeneratedValue(strategy = IDENTITY)`. |
| `name` | `varchar(200)` | no | — | Display name, and the value referenced by `physical_media.physical_media_type`. Unique. Trimmed by the service before save. |
| `description` | `text` | yes | — | Human-readable description shown in the admin catalog UI. |
| `extension` | `varchar(50)` | yes | — | Default 1 of 9 — capture-format technical. Copied into `physical_media.extension` by the frontend on type selection. |
| `bit_or_color_depth` | `varchar(100)` | yes | — | Default 2 of 9 — capture-format technical. |
| `sample_or_frame_rate` | `varchar(100)` | yes | — | Default 3 of 9 — capture-format technical. |
| `channels_or_resolution` | `varchar(100)` | yes | — | Default 4 of 9 — capture-format technical. |
| `playback_model` | `text` | yes | — | Default 5 of 9 — capture-chain hardware. |
| `capture_interface` | `text` | yes | — | Default 6 of 9 — capture-chain hardware. |
| `signal_interface` | `text` | yes | — | Default 7 of 9 — capture-chain hardware. |
| `ingest_software` | `text` | yes | — | Default 8 of 9 — capture-chain software. |
| `format_codec` | `varchar(200)` | yes | — | Default 9 of 9 — capture-chain software. |
| `created_at` | `timestamp(6) with time zone` | yes | — | Set in `@PrePersist` when null. |
| `updated_at` | `timestamp(6) with time zone` | yes | — | Set in `@PrePersist` when null, overwritten in `@PreUpdate`. |
| `created_by` | `varchar(120)` | yes | — | Actor username. `system-seed` for seeded rows, `system-import` (or the import actor) for auto-created rows. |
| `updated_by` | `varchar(120)` | yes | — | Actor username of the last edit. |
| `version` | `bigint` | no | `0` | `@Version` optimistic-lock counter with `@ColumnDefault("0")`. |

The nine default columns mirror the `physical_media` column names and types exactly, which is
what lets the frontend autofill be a straight field-for-field copy.

### Keys and constraints

| Kind | Name | Definition |
|---|---|---|
| Primary key | Hibernate default (`physical_media_types_pkey`) | `(id)`, identity-generated |
| Unique | `uk_pmt_name` | `(name)` — `@UniqueConstraint(name = "uk_pmt_name", columnNames = "name")` |
| Not null | — | `id` (identity PK), `name`, `version` |
| CHECK | — | None. No column on this table is an `@Enumerated`, so Hibernate generates no enum CHECK and no initializer adds one. |

**No foreign keys** in either direction. Nothing references `physical_media_types.id`; the
inventory table references `name` as a plain string.

### Indexes

| Index | Columns | Created by | Why it exists |
|---|---|---|---|
| `idx_pmt_name` | `name` | Hibernate (`@Index`) | `findByName` / `existsByName`, called on every manual create validation and on every imported row. Redundant with the b-tree behind `uk_pmt_name`. |

No initializer bean creates an index on this table.

### Relationships

None. `PhysicalMediaType` declares no JPA association. Its only link to `physical_media` is the
name-string match described above.

### Notes

- **Defaults, not constraints.** The nine technical columns are copied into a new inventory row
  as a UI convenience at type-selection time. A record can override any of them at create or via
  `PATCH`, and editing a catalog default never rewrites inventory rows that already carry a
  value — historic capture metadata stays exactly as it was recorded.
- **`ensureExists` writes rows behind the admin's back.** When an import carries an unknown type,
  the row is created with all nine defaults `NULL` and `description = "Auto-created during Excel
  import."`. Watch for those after a large import — they are the ones an admin still needs to
  fill in.
- **Delete is guarded in application code only.** `PhysicalMediaTypeService.delete` loads
  `mediaRepository.findAll()` and filters in memory to count usages. On a 4,400-row inventory
  that is a full table read per delete attempt. A direct `DELETE` in `psql` bypasses the guard
  entirely and will orphan inventory rows, because there is no FK to stop it.
- **Listing is `findAll(Sort.by(ASC, "name"))`** (`findAllOrderedByName`), so ordering is the
  database's default collation on `name`.

---

## Seeded type catalog

`platform/config/PhysicalMediaTypeSeeder.java` runs on `ApplicationReadyEvent` inside a
`@Transactional` boundary and pre-populates the six media types observed in
`All Final Archive Lists.xlsx → Sheet1`, each with the technical defaults the team standardised
on.

The seeder is **idempotent and non-destructive**: for each seed it calls
`repository.existsByName(seed.getName())` and inserts only when absent. An existing row is left
untouched, so an admin who updated `playback_model` after a hardware upgrade keeps that edit
across restarts. To force a row back to the shipped values, delete it and restart.

Seeded rows are stamped `created_by = updated_by = "system-seed"`.

| `name` | `extension` | `bit_or_color_depth` | `sample_or_frame_rate` | `channels_or_resolution` | `playback_model` | `capture_interface` | `signal_interface` | `ingest_software` | `format_codec` |
|---|---|---|---|---|---|---|---|---|---|
| `Audio Cassette` | `wav` | `24` | `48000` | `Stereo` | `Pioneer Stereo Double Cassette Deck CT-W2O8R` | `MOTO 896mk3 hybrid` | `RCA` | `Adobe Audition` | `PCM` |
| `Reel` | `wav` | `24` | `48000` | `Stereo` | `AKAI X-201D` | `MOTO 896mk3 hybrid` | `RCA` | `Adobe Audition` | `PCM` |
| `Vinyl Record` | `wav` | `24` | `48000` | `Stereo` | `Audio-Technica AT-LP60` | `MOTO 896mk3 hybrid` | `RCA` | `Adobe Audition` | `PCM` |
| `VHS Cassette` | `avi` | `8` | `25` | `720X576` | `Sony DVD Player / Video Cassette Recorder SLV-D985P ME` | `Blackmagic Intensity Pro 4K` | `Composite` | `Blackmagic Media Express` | `Uncompressed avi 8-bit YUV, 625i50 PAL` |
| `MiniDV` | `avi` | `8` | `25` | `720x576` | `Sony HVR M10` | `FireWire 400` | `FireWire IEEE 1394` | `Adobe Premiere` | `DV(Native)` |
| `CD/DVD` | — | — | — | — | — | — | — | — | — |

Seeded `description` values:

| `name` | `description` |
|---|---|
| `Audio Cassette` | `Compact audio cassette tape; 4-track stereo, ~1.875 ips.` |
| `Reel` | `Open-reel magnetic tape, typically 1/4-inch.` |
| `Vinyl Record` | `LP / 7-inch / 12-inch vinyl record.` |
| `VHS Cassette` | `VHS video cassette; PAL 625i.` |
| `MiniDV` | `MiniDV digital video cassette captured over FireWire.` |
| `CD/DVD` | `Compact disc / DVD optical media. Capture defaults to be filled when the team picks the ingest chain.` |

`CD/DVD` ships with all nine defaults `NULL` on purpose — the comment in the seeder reads
`// intentionally empty defaults — admin fills them in later`, pending the team's choice of
ingest chain. `VHS Cassette` and `MiniDV` differ in the casing of their resolution string
(`720X576` vs `720x576`); that is what the source says, and it is copied verbatim.

---

## `DigitizationStatus` and its CHECK constraint

`platform/enums/DigitizationStatus.java` encodes the sheet's `Digitization` column. The sheet
uses integer codes; the column stores the enum **name** because `@Enumerated(EnumType.STRING)` is
declared on the field, so analytics queries read a stable label instead of remembering what each
integer meant.

| Excel code | Stored value | Meaning |
|---|---|---|
| `0` | `NOT_DIGITIZED` | Physical only, no digital copy yet |
| `1` | `DIGITIZED` | Captured at least once into the archive |
| `2` | `DUPLICATED` | Captured more than once (intentionally re-ingested) |
| blank cell | `NULL` | Unknown status |

`DigitizationStatus.fromCode(Integer)` returns `null` for `null` input so the importer does not
have to null-check, and throws `IllegalArgumentException` on any unrecognized code.

### Why an initializer, not Hibernate

Hibernate writes the enum CHECK constraint **once**, when it first creates the column, and never
refreshes it under `ddl-auto=update`. Adding a fourth status would leave the old two- or
three-value CHECK in place and every insert of the new status would fail.
`platform/config/PhysicalMediaDigitizationConstraintInitializer.java` closes that gap on every
`ApplicationReadyEvent` — the same pattern as the audit-action initializers.

Step 1 — find every CHECK constraint currently attached to the `digitization` column:

```sql
SELECT con.conname
FROM pg_constraint con
JOIN pg_class c ON c.oid = con.conrelid
JOIN pg_attribute a
  ON a.attrelid = c.oid AND a.attnum = ANY(con.conkey)
WHERE c.relname = 'physical_media'
  AND con.contype = 'c'
  AND a.attname = 'digitization'
```

Step 2 — drop each one by name:

```sql
ALTER TABLE physical_media DROP CONSTRAINT IF EXISTS "<conname>"
```

Step 3 — add the constraint back, built from `DigitizationStatus.values()` joined as
`'NAME','NAME',…`:

```sql
ALTER TABLE physical_media ADD CONSTRAINT physical_media_digitization_check
CHECK (digitization IS NULL OR digitization IN ('NOT_DIGITIZED','DIGITIZED','DUPLICATED'))
```

The whole method is wrapped in `try/catch`; a failure logs
`"Could not re-sync physical_media_digitization_check: …"` at WARN and **does not stop startup**.
That matters: if the constraint fails to apply, the app boots anyway with whatever CHECK was
there before.

Two behaviors to know before you touch this column:

- The drop step matches on `a.attnum = ANY(con.conkey)`, so **any** multi-column CHECK that
  happens to include `digitization` would also be dropped and never restored. Do not add one.
- Adding a constant to `DigitizationStatus` is sufficient — no migration file, no manual DDL. The
  next boot re-synchronizes the constraint. Removing a constant, however, will make the `ALTER
  TABLE ADD CONSTRAINT` fail if any row still holds the removed value; clean the data first.

---

## The source sheet's per-type `Number` ranges

`inventory_number` is a per-media-type counter, as described above. This is the distribution it
carried in `All Final Archive Lists.xlsx → Sheet1` at the first import — the figures to
sanity-check a re-import against, and the reference for deciding whether a gap in a type's
sequence is a purge or a mistake in the sheet. The six types are exactly the ones
`PhysicalMediaTypeSeeder` ships; see [Seeded type catalog](#seeded-type-catalog).

| Physical media type | Rows | `Number` range |
|---|---|---|
| `Audio Cassette` | 1,893 | 1..1893 |
| `CD/DVD` | 1,743 | 1..1743 |
| `Reel` | 382 | 1..382 |
| `Vinyl Record` | 353 | 1..353 |
| `VHS Cassette` | 55 | 1..55 |
| `MiniDV` | 1 | 1 |

Two things follow from that shape:

- **The value is meaningless without its type.** The counter is contiguous within a type and
  starts at 1, so `nextInventoryNumber` mints 1894 for the next `Audio Cassette` and 2 for the
  next `MiniDV`.
- **Grouping or joining on `inventory_number` alone fans out six ways**, because the same small
  integers recur once per type. Pair it with `physical_media_type`, or use `pm_code`.

These figures are a snapshot of the first import, not a live count.

---

## The natural key, and why it is not enforced

`(physical_media_type, physical_label)` reads like a natural key — a label is unique within a
media type in the archive's own filing practice. **It is not enforced anywhere**: there is no
unique constraint, no composite index, and no lookup query on the pair in
`PhysicalMediaRepository`.

The importer used to upsert on that pair. `PhysicalMediaService.insertFromImport` documents why
it no longer does:

> Every row becomes a new record. `pmCode` is the unique business key; the sheet's
> `(physical_media_type, physical_label)` pair is *not* unique — two artefacts that share a label
> are still two artefacts. The importer used to upsert on that pair, which silently merged
> distinct physical tapes; that behaviour has been removed.

The uniqueness that *is* enforced is `uk_pm_code` on `pm_code`, generated server-side, which is
why `PhysicalMedia`'s own javadoc calls it "our unique business key so a row can be referenced
even when the user-supplied `physicalLabel` from the sheet repeats across media types".

> **Stale comment warning.** The class javadoc on
> `platform/service/physicalmedia/PhysicalMediaExcelImportService.java` still reads
> *"Dedupe: rows are upserted by `(physicalMediaType, physicalLabel)`. Rows missing both are
> always inserted."* and refers to a `PhysicalMediaService#upsertFromImport` method. That method
> does not exist. The import loop calls `physicalMediaService.insertFromImport(dto, actor)`. Trust
> the service, not the importer's javadoc — **re-importing the same sheet twice inserts every row
> twice.**

Practical consequences for anyone writing a query:

- Do not `GROUP BY (physical_media_type, physical_label)` expecting one row per artefact.
- Joining anything to `physical_label` is a fan-out risk. Join on `pm_code`, or on `id`.
- De-duplicating an accidental double import is a manual job keyed on `pm_code` ranges or on
  `created_at` / `source = 'IMPORT'`.

---

## Column migration: `PhysicalMediaSizeColumnMigrationInitializer`

`platform/config/PhysicalMediaSizeColumnMigrationInitializer.java` is the reference example of a
hand-written column migration in this codebase — worth reading before you write your own, since
there is no Flyway or Liquibase to fall back on.

### What changed on the entity

Two columns changed meaning at once:

| Legacy column | New column | What happens to the data |
|---|---|---|
| `size` (material size — big / medium / normal / small) | `physical_size` | **Copied.** Hibernate creates `physical_size` under `ddl-auto=update` but never moves data into it, so existing rows would appear to lose their size. |
| `sub_type` (retired) | `size_gb` (digital file size in GB) | **Not copied, deliberately.** The old sub-type text means nothing as a file size; `size_gb` starts empty. |

Neither legacy column is dropped. `ddl-auto=update` never drops anything, and the initializer
does not either — both `size` and `sub_type` linger in the table as orphans until someone removes
them by hand. The javadoc supplies the statement for the one it is safe to drop immediately:

```sql
ALTER TABLE physical_media DROP COLUMN sub_type;
```

### The SQL it runs

The guard, run once per column name via `columnExists("size")` and `columnExists("physical_size")`:

```sql
SELECT COUNT(*) FROM information_schema.columns
WHERE table_name = 'physical_media' AND column_name = ?
```

If either column is absent the method returns immediately — that is the fresh-database case (no
legacy `size` yet) and the already-cleaned-up case (legacy `size` dropped) in one check.

The backfill itself:

```sql
UPDATE physical_media SET physical_size = size
WHERE physical_size IS NULL AND size IS NOT NULL
```

On success with `updated > 0` it logs
`"Backfilled physical_media.physical_size from legacy 'size' column for {} row(s)"`. Like the
constraint initializer, the whole body is wrapped in `try/catch` and only logs a WARN on failure,
so a broken migration never blocks startup.

### Why it is idempotent

Three independent reasons, which is what makes it safe to leave wired up permanently rather than
deleting it after one deploy:

1. **The `WHERE` clause is self-limiting.** `physical_size IS NULL` means a row that was already
   backfilled is not matched a second time. After the first pass the statement updates zero rows —
   a cheap no-op on every subsequent boot.
2. **The `information_schema` guard short-circuits.** Once the legacy `size` column is dropped, or
   on a database created after the rename where it never existed, `columnExists("size")` returns
   `false` and the method returns before issuing any `UPDATE`.
3. **It runs on `ApplicationReadyEvent`, after Hibernate's schema update.** `physical_size` is
   guaranteed to exist by the time the `UPDATE` runs, so the ordering cannot produce a
   "column does not exist" error on a fresh boot.

Note what idempotent does *not* mean here: the backfill only fills `NULL`s. A row whose
`physical_size` was edited after the migration is never overwritten, but equally, a row whose
`physical_size` was set to `''` (empty string rather than `NULL`) will not be backfilled either.

### The pattern to copy

Every hand-written migration in `platform/config/` follows the same four rules, and a new one
should too:

1. Listen on `@EventListener(ApplicationReadyEvent.class)` so Hibernate's DDL has already run.
2. Guard on `information_schema` (or `pg_constraint`) rather than assuming the current shape.
3. Write the `WHERE` clause so a second run is a no-op.
4. Catch and log at WARN — a migration must never prevent the application from starting.

---

## Related

- [Database documentation index](./README.md)
- [Schema — Audit and Activity-Log Tables](./schema-audit.md) — `physical_media_audit_logs`, the
  `PhysicalMediaAuditAction` CHECK constraint, and `AuditLogIndexInitializer`
- [Schema Migrations](./migrations.md) — the full inventory of `ApplicationReadyEvent`
  initializer beans that stand in for Flyway
- [Indexes and query performance](./indexes-and-performance.md) — how the index set above
  behaves under the list, search and analytics queries
