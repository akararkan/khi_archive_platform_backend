# Sort & Filter Reference

How listing, filtering, and **sorting** work across every entity in the KHI
Archive backend — the query params each list endpoint accepts, the exact
`sortBy` keys (and their synonyms), and the "fast sort" routing that decides
whether ordering happens in the database or in memory.

> TL;DR for the frontend: drive ordering **only** with `sortBy` +
> `sortDirection`. You never need Spring's `sort=field,dir` param — and for the
> DB-paged entities (Maqam, Physical Media) it can even 500 on an unknown
> property. `sortBy` is always honoured, on every path.

---

## 1. The two listing families

Every filtered list returns Spring Data's `Page<T>` (`content`, `totalElements`,
`totalPages`, `number`, `size`, …). Filters bind from the query string into a
`*FilterParams` object via `@ModelAttribute`. But the *engine* underneath comes
in two shapes:

### A. Cache-backed entities — Audio, Image, Video, Text, Person, Category
The active list lives in an in-memory Caffeine read-cache (`*ReadCache`). Every
list call runs the full pipeline in memory:

```
readCache.getAllActive()  →  *FilterSupport.applyFiltersAndSort(list, params)  →  PaginationSupport.sliceList(view, pageable)
```

- Filtering and sorting are **100% in-memory** over the cached DTO list.
- Spring's `sort=field,dir` (the `Pageable`'s own sort) is **ignored** — ordering
  is driven **only** by `sortBy` / `sortDirection`.
- `sortBy` is therefore **always** honoured, at no extra DB cost (the set is
  already in memory).

### B. DB-paged entities — Maqam, Physical Media
No read-cache (deliberately — see `maqam_physicalmedia_filters` notes). The
service picks the **cheapest correct path** per request:

| Request | Path | Ordering |
|---|---|---|
| No params at all | Fast DB page | `@PageableDefault` (Physical Media: `id ASC`; Maqam: DB order) |
| **Sort only**, on a DB-mappable key | Fast DB page | `ORDER BY` pushed into the DB (one page loaded) |
| Any real filter present | In-memory | `sortBy` comparator over the full active set |
| Sort by a **derived** key (Physical Media `digitization`) | In-memory | comparator (DB has no column for it) |
| Maqam: a **sorting teacher** | In-memory | comparator over the teacher's assigned set* |

\* The teacher listing uses a `SELECT DISTINCT … JOIN` that PostgreSQL won't let
us `ORDER BY LOWER(...)` on, so a sorting teacher is served in memory over their
(small) assigned set. Admins/employees push the sort to the DB.

**Consistency guarantee.** The DB `ORDER BY` is built (`SortSupport`) to match
the in-memory comparators exactly, so a row lands in the same position on either
path:

- Text keys → `ORDER BY LOWER(col)` ↔ `String.CASE_INSENSITIVE_ORDER`.
- NULLs → DB native placement (PostgreSQL: `NULLS LAST` for ASC, `NULLS FIRST`
  for DESC) ↔ `Comparator.nullsLast(...)` and its `.reversed()`.

### Why this is the fast path
- The common cases (no params; sort-only) never leave the database and load a
  single page — index-friendly, O(page).
- The full-set scan happens **only** when a filter genuinely needs it, and even
  then it's a single linear pass with cheap-first short-circuiting
  (boolean/enum → numeric range → date range → equals → contains → collections)
  over a few thousand rows: microseconds.
- Unknown `sortBy` → treated as "no sort" → stays on the fast path (default
  order). It never throws.

---

## 2. Sort keys (`sortBy`) per entity

`sortDirection` is `asc` (default) or `desc`, everywhere. Keys are
case-insensitive. An unrecognised `sortBy` = no sort (default order). Text keys
sort case-insensitively; `desc` places nulls first.

### Audio — `GET /api/audio`
| Sorts by | Accepted `sortBy` values |
|---|---|
| Audio code | `audioCode`, `code` |
| Title | `originTitle`, `title`, `name`, `alpha`, `alphabet`, `alphabetical` |
| Created (audit) | `createdAt`, `created`, `added`, `dateAdded`, `date_added` |
| Updated (audit) | `updatedAt`, `updated`, `modified`, `dateModified`, `date_modified` |
| Date created (field) | `dateCreated`, `date_created` |
| Date published | `datePublished`, `date_published`, `published` |
| Date modified (field) | `dateModifiedField`, `dateMod` |
| Date copyrighted | `dateCopyrighted`, `copyrighted` |
| Audio quality | `audioQuality`, `audioQualityOutOf10`, `quality` |
| Version number | `versionNumber`, `version` |
| Copy number | `copyNumber`, `copy` |

### Image — `GET /api/image`
| Sorts by | Accepted `sortBy` values |
|---|---|
| Image code | `imageCode`, `code` |
| Title | `originalTitle`, `title`, `name`, `alpha`, `alphabet`, `alphabetical` |
| Created (audit) | `createdAt`, `created`, `added`, `dateAdded`, `date_added` |
| Updated (audit) | `updatedAt`, `updated`, `modified`, `dateModified`, `date_modified` |
| Date created (field) | `dateCreated`, `date_created` |
| Date modified (field) | `dateModifiedField`, `dateMod` |
| Date published | `datePublished`, `date_published`, `published` |
| Date copyrighted | `dateCopyrighted`, `copyrighted` |
| Version number | `versionNumber`, `version` |
| Copy number | `copyNumber`, `copy` |

### Video — `GET /api/video`
| Sorts by | Accepted `sortBy` values |
|---|---|
| Video code | `videoCode`, `code` |
| Title | `originalTitle`, `title`, `name`, `alpha`, `alphabet`, `alphabetical` |
| Language | `language`, `lang` |
| Created (audit) | `createdAt`, `created`, `added`, `dateAdded`, `date_added` |
| Updated (audit) | `updatedAt`, `updated`, `modified`, `dateModified`, `date_modified` |
| Date created (field) | `dateCreated`, `date_created` |
| Date modified (field) | `dateModifiedField`, `dateMod` |
| Date published | `datePublished`, `date_published`, `published` |
| Date copyrighted | `dateCopyrighted`, `copyrighted` |
| Version number | `versionNumber`, `version` |
| Copy number | `copyNumber`, `copy` |

### Text — `GET /api/text`
| Sorts by | Accepted `sortBy` values |
|---|---|
| Text code | `textCode`, `code` |
| Title | `originalTitle`, `title`, `name`, `alpha`, `alphabet`, `alphabetical` |
| Author | `author` |
| Language | `language`, `lang` |
| Created (audit) | `createdAt`, `created`, `added`, `dateAdded`, `date_added` |
| Updated (audit) | `updatedAt`, `updated`, `modified`, `dateModified`, `date_modified` |
| Date created (field) | `dateCreated`, `date_created` |
| Print date | `printDate`, `print_date` |
| Date modified (field) | `dateModifiedField`, `dateMod` |
| Date published | `datePublished`, `date_published`, `published` |
| Date copyrighted | `dateCopyrighted`, `copyrighted` |
| Version number | `versionNumber`, `version` |
| Copy number | `copyNumber`, `copy` |
| Page count | `pageCount`, `pages`, `page_count` |

### Person — `GET /api/person`
| Sorts by | Accepted `sortBy` values |
|---|---|
| Full name | `fullName`, `name`, `alpha`, `alphabet`, `alphabetical` |
| Created (audit) | `createdAt`, `created`, `added`, `dateAdded`, `date_added` |
| Updated (audit) | `updatedAt`, `updated`, `modified`, `dateModified`, `date_modified` |
| Date of birth | `dateOfBirth`, `dob`, `birth`, `date_of_birth` |
| Date of death | `dateOfDeath`, `dod`, `death`, `date_of_death` |

### Category — `GET /api/category`
| Sorts by | Accepted `sortBy` values |
|---|---|
| Name | `name`, `alpha`, `alphabet`, `alphabetical` |
| Created (audit) | `createdAt`, `created`, `added`, `dateAdded`, `date_added` |
| Updated (audit) | `updatedAt`, `updated`, `modified`, `dateModified`, `date_modified` |

### Maqam — `GET /api/maqam` (and `GET /api/admin/maqam/trash`)
All keys map to a real column ⇒ eligible for the DB fast path.
| Sorts by | Accepted `sortBy` values |
|---|---|
| Maqam code | `maqamCode`, `code` |
| Song name | `songName`, `song`, `title`, `name`, `alpha`, `alphabet`, `alphabetical` |
| Producer (singer) | `producer`, `singer` |
| Audio duration (sec) | `duration`, `durationSeconds`, `audioDurationSeconds` |
| Created (audit) | `createdAt`, `created`, `added`, `dateAdded`, `date_added` |
| Updated (audit) | `updatedAt`, `updated`, `modified`, `dateModified`, `date_modified` |

### Physical Media — `GET /api/physical-media` (and `GET /api/admin/physical-media/trash`)
| Sorts by | Accepted `sortBy` values | DB fast path? |
|---|---|---|
| PM code | `pmCode`, `code` | yes |
| Inventory number | `inventoryNumber`, `number`, `inventory` | yes |
| Row number | `rowNumber`, `row`, `no` | yes |
| Media type | `physicalMediaType`, `type`, `mediaType` | yes |
| Media category | `mediaCategory`, `category` | yes |
| Title | `title`, `name`, `alpha`, `alphabet`, `alphabetical` | yes |
| Physical label | `physicalLabel`, `label` | yes |
| Owner | `owner` | yes |
| Year | `year` | yes |
| Duration (min) | `duration`, `durationMin`, `durationMinutes` | yes |
| Track numbers | `trackNumbers`, `tracks` | yes |
| Digitize date | `digitizeDate`, `digitized` | yes |
| Created (audit) | `createdAt`, `created`, `added`, `dateAdded`, `date_added` | yes |
| Updated (audit) | `updatedAt`, `updated`, `modified`, `dateModified`, `date_modified` | yes |
| Digitization status | `digitization`, `digitizationCode` | **no** — ordered by the derived `0/1/2` code, so this sort runs in memory |

### Project — `GET /api/project`
No filter/sort params (pageable only).

---

## 3. Filter operator taxonomy

The same operator families appear on every `*FilterParams`:

- **Categorical equals** — case-insensitive exact match (`String` field).
- **Long-text contains** — case-insensitive substring (`String` field).
- **Collection any/all** — a `List<String>` field + a paired `xxxMatch`
  (`any` default | `all`). *Only on the cache-backed entities* whose columns are
  real arrays (tags, keywords, genres, …).
- **Boolean** — `true` | `false`.
- **Enum** — by name (case-insensitive) and/or numeric code.
- **Numeric range** — inclusive `xxxMin` / `xxxMax`.
- **Date range** — inclusive. `LocalDate` fields take `YYYY-MM-DD`; `Instant`
  (audit) fields take ISO-8601 instants (`…T00:00:00Z`). Both ends inclusive.

---

## 4. Filter fields — Maqam & Physical Media (full)

### Maqam — `GET /api/maqam`  ·  auth `maqam:read`  ·  trash `GET /api/admin/maqam/trash` (`maqam:delete`)
- **Contains**: `songName`, `producer`, `maqamCode`, `archiveNote`,
  `audioFileName`, `createdBy`, `updatedBy`.
- **Numeric range** (audio seconds): `durationSecondsMin`, `durationSecondsMax`.
- **Date range** (instants): `createdFrom`/`createdTo`, `updatedFrom`/`updatedTo`.
- **Teacher-panel filters**:
  - `teacherUserId` — records that user id is on the panel of.
  - `teacherUsername` — contains, any panel member.
  - `maqamType` — case-insensitive exact; any panel member voted it.
  - `assignmentStatus` — `assigned` | `unassigned`.
  - `voteStatus` — `none` (no vote cast) | `partial` (some assigned teachers
    voted) | `full` (all voted).

Examples:
```
GET /api/maqam?voteStatus=none&assignmentStatus=assigned
GET /api/maqam?maqamType=Rast&sortBy=songName&sortDirection=asc
GET /api/maqam?teacherUserId=42&durationSecondsMin=120&sortBy=duration&sortDirection=desc
GET /api/maqam?sortBy=createdAt&sortDirection=desc          # sort-only → DB fast path (admin/employee)
```

### Physical Media — `GET /api/physical-media`  ·  auth `physical_media:read`  ·  trash `GET /api/admin/physical-media/trash` (`physical_media:delete`)
- **Equals** (case-insensitive): `physicalMediaType`, `mediaCategory`,
  `physicalSize`, `extension`, `formatCodec`, `source` (`MANUAL` | `IMPORT`).
- **Enum / boolean**: `digitization` (`NOT_DIGITIZED` | `DIGITIZED` |
  `DUPLICATED`) or `digitizationCode` (`0` | `1` | `2`); `needToClear`
  (`true`|`false`) or `needToClearCode` (`0`|`1`).
- **Contains**: `pmCode`, `title`, `physicalLabel`, `content`, `archiveDepNote`,
  `owner`, `tags`, `trackName`, `captureDepNote`, `sizeGB`, `playbackModel`,
  `captureInterface`, `signalInterface`, `ingestSoftware`, `bitOrColorDepth`,
  `sampleOrFrameRate`, `channelsOrResolution`, `createdBy`, `updatedBy`.
- **Numeric ranges**: `yearMin`/`yearMax`, `durationMinutesMin`/`durationMinutesMax`,
  `trackNumbersMin`/`trackNumbersMax`, `inventoryNumberMin`/`inventoryNumberMax`,
  `rowNumberMin`/`rowNumberMax`.
- **Date ranges**: `digitizeDateFrom`/`digitizeDateTo` (`YYYY-MM-DD`),
  `createdFrom`/`createdTo`, `updatedFrom`/`updatedTo` (instants).

Examples:
```
GET /api/physical-media?mediaCategory=Video&yearMin=1980&yearMax=1999&sortBy=year&sortDirection=desc
GET /api/physical-media?digitization=NOT_DIGITIZED&needToClear=true
GET /api/physical-media?title=wedding&owner=kaka&sortBy=inventoryNumber
GET /api/physical-media?sortBy=type                          # sort-only → DB fast path
GET /api/physical-media?sortBy=digitization&sortDirection=asc # derived key → in-memory sort
```

Filter fields for the cache-backed entities (Audio ≈ 20 equals + 15 contains +
4 collections + ranges; Image similar; Video/Text/Person/Category smaller) are
enumerated in each entity's `*FilterParams` class javadoc.

---

## 5. Where it lives (code map)

| Piece | Location |
|---|---|
| Sort→`Sort` builder (shared) | `platform/service/common/SortSupport.java` |
| In-memory slicer (shared) | `platform/service/common/PaginationSupport.java` |
| Filter params (per entity) | `platform/dto/{entity}/*FilterParams.java` |
| Filter+sort engine (per entity) | `platform/service/{entity}/*FilterSupport.java` |
| Fast-path routing (DB-paged) | `MaqamService.listActive/listTrash`, `PhysicalMediaService.listActive/listTrash` |

**Adding a sort key**: add the `case` to that entity's
`*FilterSupport.comparatorFor(...)` (in-memory), and — if it maps to a real
column and you want it on the DB fast path — mirror it in `resolveDbSort(...)`
using `SortSupport.ci(...)` (text) or `SortSupport.plain(...)` (number/date).
Leave it out of `resolveDbSort` (returns `Sort.unsorted()`) to force it
in-memory, the way Physical Media's `digitization` does.
