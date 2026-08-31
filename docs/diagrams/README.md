# Diagrams

> **Audience:** anyone reading or extending the backend · **Scope:** `docs/diagrams/` ·
> **Source:** the Java source under `src/main/java/ak/dev/khi_archive_platform/`

Forty-five diagrams of the KHI Archive Platform backend: 14 entity-relationship diagrams,
19 UML diagrams (8 class, 8 sequence, 3 state) and 12 flowcharts. Each was written by reading
the Java source, then checked back against it.

One flat folder, no subfolders: 45 `.svg` files and this index. Open an SVG in a browser, or click
the filename in the tables below — GitHub renders SVG inline.

## The whole schema on one sheet

[**`er-00-full-schema.svg`**](./er-00-full-schema.svg) — **all 59 tables in a single diagram**,
with their key columns and every structural relationship between them. This is the wall chart: if
you want to see the shape of the entire database at once rather than one area at a time, start
here. The thirteen `er-01` … `er-08c` diagrams below are the same content split by area, and they
are the ones to read when you are working inside one part of the schema.

It is a big picture — roughly 2,300 × 13,500 px — so it is built tall and narrow on purpose:
scroll down through it in a browser rather than sideways. What it draws:

| | |
|---|---|
| **Solid line** | A real foreign key or an `@ElementCollection` side table owned by its parent |
| **Dashed line** | A logical link with **no** foreign key — an `id` or business code carried as a plain column and joined in service code. These can dangle; nothing in the database stops them |
| **Columns shown** | Primary key, foreign keys, the business code, and the few columns that define the row. Full column lists live in [`../internal/database/`](../internal/database/README.md) |
| **Deliberately not drawn** | Every `*_audit_logs` table also snapshots `actor_user_id` into `users_tbl` and `session_id` into `sessions`. That is 24 more edges, uniform across all twelve tables, and drawing them turns `users_tbl` into a hairball — so each audit table draws exactly one edge, to the thing it audits |

## Entity relationship diagrams

The tables, their columns at key level, and the relationships between them. For the full
column-by-column reference see [`../internal/database/`](../internal/database/README.md); for the
DDL and diagnostic SQL as runnable files see [`../database/`](../database/README.md).

| Diagram | Shows |
|---|---|
| [`er-00-full-schema.svg`](./er-00-full-schema.svg) | **All 59 tables on one sheet** — the complete schema, key columns and every structural relationship |
| [`er-01-high-level-map.svg`](./er-01-high-level-map.svg) | High-level map — the major entities and the paths between them |
| [`er-02-content-core.svg`](./er-02-content-core.svg) | Content core — projects, categories, persons and the four media tables |
| [`er-03a-audio-collections.svg`](./er-03a-audio-collections.svg) | Audio element-collection tables |
| [`er-03b-video-collections.svg`](./er-03b-video-collections.svg) | Video element-collection tables |
| [`er-03c-image-collections.svg`](./er-03c-image-collections.svg) | Image element-collection tables |
| [`er-03d-text-collections.svg`](./er-03d-text-collections.svg) | Text element-collection tables |
| [`er-04-users-and-security.svg`](./er-04-users-and-security.svg) | Users and security — accounts, sessions, revocation and warnings |
| [`er-05-maqam.svg`](./er-05-maqam.svg) | Maqam — song records, the teacher vote panel and listen sessions |
| [`er-06-physical-media.svg`](./er-06-physical-media.svg) | Physical media — the inventory table and its type catalog |
| [`er-07-corrections.svg`](./er-07-corrections.svg) | Corrections — guest submissions, their audit trail and the warning link |
| [`er-08a-media-audit-logs.svg`](./er-08a-media-audit-logs.svg) | Audit logs for the four media types |
| [`er-08b-catalog-audit-logs.svg`](./er-08b-catalog-audit-logs.svg) | Audit logs for catalog and inventory |
| [`er-08c-system-and-guest-logs.svg`](./er-08c-system-and-guest-logs.svg) | System, user and guest activity logs |

## UML diagrams

### Class diagrams

| Diagram | Shows |
|---|---|
| [`uml-class-01-media-entities.svg`](./uml-class-01-media-entities.svg) | Media entities: Audio, Video, Image, Text (and their owning Project) |
| [`uml-class-02-core-entities.svg`](./uml-class-02-core-entities.svg) | Core entities: Project, Category, Person, KhiLogo (KHI Archive Platform backend) |
| [`uml-class-03-user-security.svg`](./uml-class-03-user-security.svg) | User and security model (KHI Archive Platform backend) |
| [`uml-class-04-maqam.svg`](./uml-class-04-maqam.svg) | Maqam domain: List-of-Maqam song records, the teacher vote panel, and audio listen tracking. |
| [`uml-class-05-physical-media.svg`](./uml-class-05-physical-media.svg) | Physical media inventory (PhysicalMedia + PhysicalMediaType catalog) |
| [`uml-class-06-corrections-and-audit.svg`](./uml-class-06-corrections-and-audit.svg) | Corrections and the audit-log family (KHI Archive Platform backend) |
| [`uml-class-07-layering.svg`](./uml-class-07-layering.svg) | Application layering - one request traced top to bottom, Audio as the worked example |
| [`uml-class-08-exception-hierarchy.svg`](./uml-class-08-exception-hierarchy.svg) | Exception hierarchy and error handling (KHI Archive Platform backend) |

### Sequence diagrams

| Diagram | Shows |
|---|---|
| [`uml-seq-01-login.svg`](./uml-seq-01-login.svg) | Login and token issue (POST /api/auth/login end to end) |
| [`uml-seq-02-authenticated-request.svg`](./uml-seq-02-authenticated-request.svg) | Authenticated request and authorization (GET /api/audio) |
| [`uml-seq-03-media-upload.svg`](./uml-seq-03-media-upload.svg) | Media upload: multipart create (POST /api/audio) |
| [`uml-seq-04-media-streaming-range.svg`](./uml-seq-04-media-streaming-range.svg) | Media streaming with byte ranges (GET .../audio/{audioCode}/stream, HTTP Range and 206) |
| [`uml-seq-05-excel-import.svg`](./uml-seq-05-excel-import.svg) | Physical media Excel import (POST /api/physical-media/import) |
| [`uml-seq-06-correction-workflow.svg`](./uml-seq-06-correction-workflow.svg) | Guest correction to employee action (submit -> review -> forward -> acknowledge -> resolve) |
| [`uml-seq-07-maqam-vote-and-listen.svg`](./uml-seq-07-maqam-vote-and-listen.svg) | Maqam listening and voting (one TEACHER working through one record) |
| [`uml-seq-08-logout-and-revocation.svg`](./uml-seq-08-logout-and-revocation.svg) | Logout and token revocation (how a stateless JWT is actually revoked) |

### State diagrams

| Diagram | Shows |
|---|---|
| [`uml-state-01-trash-lifecycle.svg`](./uml-state-01-trash-lifecycle.svg) | Content record trash lifecycle — Audio as the representative content record |
| [`uml-state-02-correction-status.svg`](./uml-state-02-correction-status.svg) | Correction status lifecycle — GuestCorrection.status (enum CorrectionStatus) |
| [`uml-state-03-warning-lifecycle.svg`](./uml-state-03-warning-lifecycle.svg) | User warning lifecycle (one user_warnings row / UserWarning entity) |

## Flowcharts

| Diagram | Shows |
|---|---|
| [`flow-01-request-lifecycle.svg`](./flow-01-request-lifecycle.svg) | HTTP request lifecycle — one request from socket to response |
| [`flow-02-authorization-resolution.svg`](./flow-02-authorization-resolution.svg) | How an authority set is resolved — why a request gets 403 ACCESS_DENIED |
| [`flow-03-list-endpoint-cache-path.svg`](./flow-03-list-endpoint-cache-path.svg) | List endpoint — cache fast path vs database fallback |
| [`flow-04-public-visibility-gate.svg`](./flow-04-public-visibility-gate.svg) | Public visibility gate - what an anonymous request must pass before a record is returned |
| [`flow-05-trash-restore-purge.svg`](./flow-05-trash-restore-purge.svg) | Trash, restore and purge — soft-delete lifecycle for media (audio shown) and projects |
| [`flow-06-project-visibility-cascade.svg`](./flow-06-project-visibility-cascade.svg) | Project visibility and the cascade to media |
| [`flow-07-guest-feed-assembly.svg`](./flow-07-guest-feed-assembly.svg) | Guest feed assembly — GET /api/guest/feed (GuestMediaFeedDTO) |
| [`flow-08-error-handling.svg`](./flow-08-error-handling.svg) | Error handling and the response envelope |
| [`flow-09-startup-initializers.svg`](./flow-09-startup-initializers.svg) | Application startup and schema initializers |
| [`flow-10-schema-change-decision.svg`](./flow-10-schema-change-decision.svg) | Making a schema change — decision tree |
| [`flow-11-tag-keyword-canonicalization.svg`](./flow-11-tag-keyword-canonicalization.svg) | Tag and keyword canonicalization — one normal form on write, one cached union on read |
| [`flow-12-two-phase-fuzzy-search.svg`](./flow-12-two-phase-fuzzy-search.svg) | Two-phase fuzzy search — GET /api/audio/search |

## Which diagram answers which question

| If you are asking... | Look at |
|---|---|
| What does the whole database look like? | [`er-00-full-schema`](./er-00-full-schema.svg) |
| How does a request actually get from the socket to my controller? | [`flow-01-request-lifecycle`](./flow-01-request-lifecycle.svg) |
| Why did my request come back `403 ACCESS_DENIED`? | [`flow-02-authorization-resolution`](./flow-02-authorization-resolution.svg) |
| Which authorities does each role hold, and where do they come from? | [`uml-class-03-user-security`](./uml-class-03-user-security.svg) |
| Why is a record visible in the back office but missing from the public site? | [`flow-04-public-visibility-gate`](./flow-04-public-visibility-gate.svg) |
| I made a project public — why are its media still hidden? | [`flow-06-project-visibility-cascade`](./flow-06-project-visibility-cascade.svg) |
| What does `DELETE` actually do to a record and its S3 file? | [`uml-state-01-trash-lifecycle`](./uml-state-01-trash-lifecycle.svg), [`flow-05-trash-restore-purge`](./flow-05-trash-restore-purge.svg) |
| How is a stateless JWT revoked at logout? | [`uml-seq-08-logout-and-revocation`](./uml-seq-08-logout-and-revocation.svg) |
| Why did my list endpoint ignore the filter I passed? | [`flow-03-list-endpoint-cache-path`](./flow-03-list-endpoint-cache-path.svg) |
| Why does an `<audio>` element seek instead of downloading the whole file? | [`uml-seq-04-media-streaming-range`](./uml-seq-04-media-streaming-range.svg) |
| Where does an uploaded file go, and what is written alongside it? | [`uml-seq-03-media-upload`](./uml-seq-03-media-upload.svg) |
| How do I add a column without breaking the running app? | [`flow-10-schema-change-decision`](./flow-10-schema-change-decision.svg) |
| What runs at startup before the app serves its first request? | [`flow-09-startup-initializers`](./flow-09-startup-initializers.svg) |
| Why did the tag I typed come back spelled differently? | [`flow-11-tag-keyword-canonicalization`](./flow-11-tag-keyword-canonicalization.svg) |
| Which handler turns my exception into the JSON the client sees? | [`flow-08-error-handling`](./flow-08-error-handling.svg), [`uml-class-08-exception-hierarchy`](./uml-class-08-exception-hierarchy.svg) |
| What happens to a correction between a guest submitting it and an employee acting on it? | [`uml-seq-06-correction-workflow`](./uml-seq-06-correction-workflow.svg), [`uml-state-02-correction-status`](./uml-state-02-correction-status.svg) |
| How does the `.xlsx` inventory import decide what is a duplicate? | [`uml-seq-05-excel-import`](./uml-seq-05-excel-import.svg) |

## Conventions

- **ER diagrams** use crow's-foot notation. Entity names are the real SQL table names. Only key
  columns are listed — PK, FKs, the business code and a few defining columns; the full column
  lists live in [`../internal/database/`](../internal/database/README.md).
- **Flowchart node shapes** carry meaning: stadium = start or terminal state, diamond = decision,
  cylinder = datastore, parallelogram = input or output, double-rectangle = invoked component,
  plain rectangle = a step.
- **Tinted nodes** mark the three things a reader most needs to spot: error termini (red), cache
  hits and success paths (green), and security gates (amber).
- **Diagrams show the representative case** where drawing the full picture would make them
  unreadable — 12–22 boxes is the legibility budget. Anything deliberately left out is named in a
  note inside the diagram rather than dropped silently.
- **Accuracy over tidiness.** Every class, field, method, endpoint, status code, column and enum
  value in these diagrams exists in the Java source; none draws a constraint the code does not
  enforce or an association JPA does not map.
- Sequence diagrams are tall and flowcharts are narrow on purpose: vertical scrolling reads
  better in a browser than horizontal.

## Editing a diagram

These SVGs are the deliverable — there is no mermaid source checked in beside them. To change one,
edit the SVG directly, or redraw the diagram and replace the file, keeping the filename so every
link in this folder and in `docs/` keeps working.

`er-00-full-schema.svg` is the one to regenerate rather than hand-edit if the schema changes: it
was assembled from the thirteen area ER diagrams plus the relationship inventory in
[`../internal/database/erd.md`](../internal/database/erd.md), which is kept current alongside the
entity classes.

For an ER view you can edit as text instead, [`../internal/database/erd.md`](../internal/database/erd.md)
holds the same entity-relationship content as inline mermaid that GitHub renders in place.

## Related

- [`../README.md`](../README.md) — the documentation index
- [`../database/README.md`](../database/README.md) — the runnable SQL: DDL, index and diagnostic scripts
- [`../internal/database/erd.md`](../internal/database/erd.md) — the ER content inline as mermaid,
  for reading in context alongside the schema tables
- [`../internal/README.md`](../internal/README.md) — the staff back-office API docs
- [`../external/README.md`](../external/README.md) — the public API docs
