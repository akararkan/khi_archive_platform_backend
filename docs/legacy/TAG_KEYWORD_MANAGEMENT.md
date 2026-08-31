# Tag & Keyword Management API

Admin CRUD over the **tag** and **keyword** vocabularies — list distinct values
with usage counts, globally **rename**, and globally **delete** — layered on top
of the existing autocomplete (`/api/tags/suggest`, `/api/keywords/suggest`).

> TL;DR: `GET/PATCH/DELETE /api/admin/tags` and `.../keywords`, ADMIN only.
> Everything runs as **set-based SQL** (one statement per table, no entity
> loading), so a global rename/delete is a handful of round-trips regardless of
> how many records carry the value.

---

## 1. Why this isn't a simple table

Tags and keywords are **not** their own table. Each is a JPA
`@ElementCollection` `List<String>` on every owning entity, i.e. a set of small
collection tables — and they're **bags** (no `@OrderColumn`, no unique
constraint), which is exactly what makes bulk SQL safe and fast.

| Vocabulary | Value column | Tables (`fk → parent`) |
|---|---|---|
| **Tags** (5) | `tag` | `audio_tags` (`audio_id→audios`), `video_tags` (`video_id→videos`), `image_tags` (`image_id→images`), `text_tags` (`text_id→texts`), `project_tags` (`project_id→projects`) |
| **Keywords** (6) | `keyword` | the same five as `*_keywords` **+** `category_keywords` (`category_id→categories`) |

A value like `sulaimaniyah` can live on an audio, a video and a project at once;
these endpoints treat the vocabulary as one cross-entity set.

**Not included — Person.** `Person.tag` / `Person.keywords` are separate
*delimited String* columns (not canonicalised, not in `/suggest`), so they are
intentionally outside this system and untouched by rename/delete.

---

## 2. Endpoints

All under `hasRole('ADMIN')`. Tags and keywords are identical in shape.

| Verb | Path | Input | Returns |
|---|---|---|---|
| `GET` | `/api/admin/tags` | `?q=` `&limit=` `&offset=` | `VocabularyItemDTO[]` |
| `PATCH` | `/api/admin/tags` | JSON body `{ from, to }` | `VocabularyRenameResult` |
| `DELETE` | `/api/admin/tags` | `?value=` | `VocabularyDeleteResult` |
| `GET` | `/api/admin/keywords` | `?q=` `&limit=` `&offset=` | `VocabularyItemDTO[]` |
| `PATCH` | `/api/admin/keywords` | JSON body `{ from, to }` | `VocabularyRenameResult` |
| `DELETE` | `/api/admin/keywords` | `?value=` | `VocabularyDeleteResult` |

### Payloads

```jsonc
// VocabularyItemDTO  (GET)
{ "value": "sulaimaniyah", "usageCount": 42 }

// VocabularyRenameRequest  (PATCH body)   — both fields required (@NotBlank)
{ "from": "folk musik", "to": "folk music" }

// VocabularyRenameResult  (PATCH response)
{ "from": "folk musik", "to": "folk music", "renamed": 37, "merged": 4 }

// VocabularyDeleteResult  (DELETE response)
{ "value": "deprecated/tag", "deleted": 12 }
```

- **GET params**: `q` optional canonical substring filter; `limit` default `100`,
  max `2000`; `offset` default `0`. Results ordered by `usageCount DESC, value ASC`.
- **`renamed`** = rows whose value was rewritten. **`merged`** = duplicate rows
  collapsed because their parent already carried the target. Net new distinct
  occurrences of `to` = `renamed − merged`.
- **`deleted`** = collection rows removed across all tables.

### Examples

```
GET    /api/admin/tags?q=sula&limit=50
GET    /api/admin/keywords?limit=500&offset=0
PATCH  /api/admin/tags        {"from":"kurdish  folk","to":"kurdish folk"}
PATCH  /api/admin/keywords    {"from":"folk musik","to":"folk music"}
DELETE /api/admin/tags?value=folk%20music         # URL-encode spaces/slashes
DELETE /api/admin/keywords?value=deprecated
```

> `DELETE` takes the value as a **query param** (URL-encoded), not a path
> segment — tags/keywords may contain spaces or `/`, which would break path
> routing.

---

## 3. The algorithm (fastest = set-based, no ORM)

Every operation is native SQL executed straight through the `EntityManager`,
driven by a trusted `CollectionTableRef` catalog (identifiers are hardcoded; the
tag/keyword **value** is always a bound parameter). No entities are loaded, so
cost is O(tables), not O(rows).

### List — one union, one group-by
```sql
WITH v AS (
  SELECT LOWER(c.tag) AS value
    FROM audio_tags c JOIN audios p ON p.id = c.audio_id
   WHERE p.removed_at IS NULL AND c.tag IS NOT NULL AND c.tag <> ''
  UNION ALL … (one leg per table)
)
SELECT value, COUNT(*) AS usage_count
  FROM v
 [WHERE value LIKE '%' || :q || '%' ESCAPE '\']
 GROUP BY value
 ORDER BY usage_count DESC, value ASC
 LIMIT :lim OFFSET :off
```
Counts join each parent on `removed_at IS NULL`, so the listing matches the
**live** `/suggest` vocabulary (trashed records don't inflate counts).

### Rename — update, then de-dupe by `ctid`
Per table:
```sql
UPDATE audio_tags SET tag = :to WHERE LOWER(tag) = :from;           -- rename

DELETE FROM audio_tags a USING audio_tags b                        -- collapse dups
 WHERE a.ctid < b.ctid
   AND a.audio_id = b.audio_id
   AND a.tag = b.tag
   AND a.tag = :to;
```
The second statement handles the one edge the rename creates: a parent that
**already** had `to` now has it twice. Because the collection is a bag (no unique
constraint), a `ctid` self-join keeps one row per `(parent, value)` — a clean
set without needing DB constraints.

### Delete — one statement per table
```sql
DELETE FROM audio_tags WHERE LOWER(tag) = :value;
```

**Scope of writes:** rename/delete affect **active *and* trashed** rows, so the
value is gone for good (restoring a trashed record can't resurrect an old tag).
Only the *list* is active-scoped.

---

## 4. Canonicalisation

`from`, `to`, `value`, and `q` are all normalised with the **same rule used on
save** before matching/writing:

- Tags → `Tags.canonicalOne` (cap **64** chars)
- Keywords → `Keywords.canonicalOne` (cap **200** chars)
- Rule: NFKC → strip zero-width joiners → trim → collapse internal whitespace →
  reject if empty or over the cap → lower-case (`Locale.ROOT`).

Consequences:
- A rename `to` is stored in the exact normal form as every other value.
- `from == to` after canonicalisation → **no-op** (`renamed: 0, merged: 0`).
- A blank or over-length `from`/`to`/`value` → **HTTP 400**.
- Matching uses `LOWER(col) = :canonical`, so legacy mixed-case rows still match.

---

## 5. Cache invalidation

Bulk SQL bypasses Hibernate, so after every **rename/delete** the service evicts
the read-cache of each owning entity:

- Tags → `AudioReadCache`, `VideoReadCache`, `ImageReadCache`, `TextReadCache`,
  `ProjectReadCache`.
- Keywords → those five **+** `CategoryReadCache`.

Each `evictAll()` already fans out (`@Caching`) to the matching
`tags:suggest` / `keywords:suggest` region, so autocomplete and every cached
list DTO reflect the change immediately. (List is read-only; it evicts nothing.)

---

## 6. Behaviour notes

- **Active vs trashed**: list counts active-parent occurrences (matches
  `/suggest`); rename/delete rewrite all rows. So a value that lives only on
  trashed records won't appear in the list, but a delete of it still cleans
  those rows.
- **No audit log yet**: these global edits are not written to an audit table
  (there is no tag/keyword audit entity). Add one if you need traceability.
- **Auth**: ADMIN only (`hasRole('ADMIN')`) — there is no `tag:*`/`keyword:*`
  permission in the catalog; the autocomplete `/suggest` endpoints remain open to
  any authenticated user.

---

## 7. Code map

| Piece | Location |
|---|---|
| Table catalog record | `platform/repo/vocabulary/CollectionTableRef.java` |
| Set-based engine (list/rename/delete) | `platform/repo/vocabulary/VocabularyBulkRepository.java` |
| DTOs | `platform/dto/vocabulary/{VocabularyItemDTO, VocabularyRenameRequest, VocabularyRenameResult, VocabularyDeleteResult}.java` |
| Services (catalog + canonicalise + evict) | `platform/service/tag/TagVocabularyService.java`, `platform/service/keyword/KeywordVocabularyService.java` |
| Controllers | `platform/api/tag/AdminTagAPI.java`, `platform/api/keyword/AdminKeywordAPI.java` |
| Canonicalisers (reused) | `platform/service/common/{Tags, Keywords}.java` |
| Autocomplete (unchanged) | `platform/api/{tag/TagAPI, keyword/KeywordAPI}.java`, `…/service/{tag/TagSuggestService, keyword/KeywordSuggestService}.java` |

**Adding a new tag/keyword-owning entity later**: add its `(table, valueColumn,
fkColumn, parentTable)` to the `TABLES` list in the relevant `*VocabularyService`
(and to the `*SuggestRepository` union), and add its `ReadCache` to that
service's eviction set.
