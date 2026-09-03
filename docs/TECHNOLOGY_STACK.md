# Technology Stack

> **Audience:** anyone joining the project, evaluating it, or deciding whether to add a dependency ·
> **Scope:** every technology used by `khi_archive_platform_backend`, what it does here, and why it
> was chosen ·
> **Sources read:** `pom.xml`, `src/main/resources/application.yaml`, every class in
> `platform/config/` and `user/configs/`, `user/jwt/JwtTokenProvider.java`,
> `platform/service/common/MediaDurationExtractor.java`,
> `platform/service/physicalmedia/PhysicalMediaExcelImportService.java`,
> `platform/seed/SeedDataLoader.java`, plus an import-level audit of the whole `src/main/java` tree ·
> **Verified:** 2026-09-01

This is the *what and why*. For how these pieces meet the React frontend see
[`FRONTEND_INTEGRATION.md`](./FRONTEND_INTEGRATION.md); for endpoint behaviour see
[`external/`](./external/README.md) and [`internal/`](./internal/README.md).

---

## 1. At a glance

| Layer | Technology | Version | Role in this project |
|---|---|---|---|
| Language | Java | 21 (target) | records, sealed types, pattern matching |
| Framework | Spring Boot | 4.0.5 | the whole application container |
| Web | Spring MVC (`spring-boot-starter-webmvc`) | Boot-managed | 36 `*API` controllers, ~232 request mappings |
| JSON | Jackson (`databind` + `datatype-jsr310`) | 2.21.0 | serialization and `java.time` |
| Validation | Hibernate Validator (`starter-validation`) | Boot-managed | `@Valid` on request DTOs |
| Persistence | Spring Data JPA + Hibernate ORM | Boot-managed | 33 `@Entity` classes |
| Database | PostgreSQL (+ `pg_trgm` extension) | 18 locally, Railway in production | records **and** the search index |
| Cache | Caffeine (`starter-cache`) | Boot-managed | 15 named in-process caches, 19 `@Cacheable` methods |
| Security | Spring Security | Boot-managed | stateless chain, `@PreAuthorize`, BCrypt |
| Tokens | `com.auth0:java-jwt` | 4.4.0 | HS256 JWTs |
| Object storage | AWS SDK for Java v2 — S3 | 2.20.30 | all archive media, streamed through the API |
| Media metadata | `metadata-extractor` + `mp3agic` | 2.19.0 / 0.9.1 | server-side duration fallback, no ffprobe |
| Spreadsheets | Apache POI + `poi-ooxml` | 5.3.0 | the `.xlsx` physical-media import |
| Async | Spring `@EnableAsync` + `@EnableScheduling` | Boot-managed | fire-and-forget trending/interaction logging |
| Ops | Spring Boot Actuator | Boot-managed | present; see §10 |
| Config | `me.paulschwarz:spring-dotenv` | 4.0.0 | `.env` → Spring properties |
| Boilerplate | Lombok | 1.18.42 | `@Data`, `@Slf4j`, `@RequiredArgsConstructor` |
| Nullability | JSpecify | 1.0.0 | `@Nullable` / `@NonNull` annotations |
| Tests | JUnit 5, Mockito, Spring test slices, H2 | Boot-managed | 7 test classes |
| Build | Maven + wrapper (`./mvnw`) | 3.9.14 available | `spring-boot-maven-plugin` |

**Deliberately absent:** Redis (Caffeine instead — §6), springdoc/Swagger (§9), Flyway or Liquibase
(§5), ffprobe or any native binary (§8).

---

## 2. Language and runtime

`<java.version>21</java.version>` with Spring Boot **4.0.5** as parent, so `maven.compiler.release=21`.
The only JDK installed on the development Mac is **OpenJDK 25.0.2** — builds compile to 21 and run on
25, which is supported. Install a JDK 21 only if a library misbehaves.

The Maven wrapper (`./mvnw`) is committed; no local Maven install is required.

---

## 3. Web layer

**Spring MVC** serves 36 `*API` controller classes under one `/api` prefix — roughly 123 GET, 53 POST,
31 DELETE, 19 PATCH and 6 PUT mappings. The heavy GET bias reflects what this system is: a catalog
read far more often than it is written.

**Jackson** is configured twice, and more simply than in the sister project:

- `application.yaml` — `indent-output: true`, `default-property-inclusion: non_null` (null fields are
  omitted, so clients must treat absence as null), `time-zone: Asia/Baghdad`.
- `JacksonConfig` — an `ObjectMapper` with `JavaTimeModule` registered and
  `WRITE_DATES_AS_TIMESTAMPS` disabled, so dates are ISO-8601 strings.

**Multipart** is handled by Boot's auto-configured converters. `MultipartJsonConfig` declares no
beans; it exists to document the `@RequestPart("data") MyDto` + `@RequestPart("file") MultipartFile`
pattern and the Postman recipe, so nobody re-adds a redundant converter.

The upload ceiling is unusually high and deliberately asymmetric:

```yaml
spring.servlet.multipart.max-file-size:    5GB
spring.servlet.multipart.max-request-size: 6GB   # 5 GB file + JSON part + boundaries
server.tomcat.max-swallow-size:            -1    # disabled
server.tomcat.max-http-form-post-size:     -1    # disabled
```

Tomcat's connector limits are switched off because they are 32-bit byte counts that overflow above
2 GB; Spring's multipart limits are what actually enforce the cap.

**Locale** is fixed: `spring.web.locale: ckb` with `locale-resolver: fixed`, and messages come from
`i18n/messages` in UTF-8 with `fallback-to-system-locale: false`. This is a Sorani-first application
by configuration, not by convention.

---

## 4. Persistence

**Spring Data JPA over Hibernate**, against **PostgreSQL**, 33 `@Entity` classes.

| Setting | Value | Why it matters |
|---|---|---|
| `ddl-auto` | `update` | **No Flyway or Liquibase.** The comment in `application.yaml` is a warning worth repeating: never switch to `create` or `create-drop`, because the `sessions` table backs token revocation and dropping it signs out every active login. |
| `open-in-view` | `false` | Lazy associations must be fetched in the service; N+1 queries surface instead of hiding. |
| `default_batch_fetch_size` | `1000` | Hibernate batches lazy loads aggressively — the right call for a catalog that renders large grids. |
| `batch_size` / `order_inserts` / `order_updates` | `100` / true / true | JDBC batching for the bulk-create endpoints and the spreadsheet import. |
| `jdbc.time_zone` | `UTC` | stored UTC, rendered `Asia/Baghdad` by Jackson |

`show-sql: true`, `format_sql: true` and `org.hibernate.orm.jdbc.bind: TRACE` are on, so the console
prints every statement with its bound parameters. That is a development choice, not an accident — it
is also why the log volume is high.

**10 native queries** back the parts JPQL cannot express, most notably the analytics reports, which
`UNION ALL` across the audit tables to answer "who did what, when" (`AnalyticsService`,
`UserActivityDTO`).

---

## 5. PostgreSQL as the search engine

There is no Elasticsearch, OpenSearch or Lucene here. Fuzzy search is **PostgreSQL `pg_trgm` with GIN
indexes**, created and maintained by the application itself.

`MediaSearchIndexInitializer`, `CategorySearchIndexInitializer` and `PersonSearchIndexInitializer` run
on `ApplicationReadyEvent`, issue `CREATE EXTENSION IF NOT EXISTS pg_trgm`, and then
`CREATE INDEX IF NOT EXISTS … USING GIN (LOWER(col) gin_trgm_ops)` over every searchable column of the
media, category and person tables. That is what makes `ILIKE '%q%'` and the `%` similarity operator
index-driven instead of a sequential scan.

The trade-off is worth stating plainly: this keeps the deployment to a single datastore — no second
service to run, back up or keep in sync — at the cost of the ranking and analyzer features a real
search engine would give. For an archive of this size that is the right trade; it stops being right
if relevance ranking or multi-language stemming becomes a requirement.

### The Initializer pattern

Ten `*Initializer` classes sit in `platform/config/`. Most of them exist for one reason, and it is a
direct consequence of `ddl-auto: update`:

> Hibernate writes a `CHECK` constraint once, when it first creates the column, and **never updates it
> afterwards**. So adding a value to an enum — a new audit action, a new digitization status — would
> break every insert using it, silently, at runtime.

So five of them drop and recreate a `CHECK` constraint on startup to match the current Java enum —
`AnalyticsAuditActionConstraintInitializer`, `GuestCorrectionAuditActionConstraintInitializer`,
`MaqamAuditActionConstraintInitializer`, `PhysicalMediaAuditActionConstraintInitializer`,
`PhysicalMediaDigitizationConstraintInitializer`. Three are the search-index initializers above, one
is `AuditLogIndexInitializer`, and `PhysicalMediaSizeColumnMigrationInitializer` performs a column
migration. `PhysicalMediaTypeSeeder` follows the same `ApplicationReadyEvent` shape to seed a
lookup table.

This is a pragmatic substitute for a migration tool, and it works — but it is exactly the machinery a
migration tool would replace. If Flyway is ever adopted, these classes are the first thing to retire.

---

## 6. Caching — Caffeine, not Redis

`CacheConfig` builds a `SimpleCacheManager` over **15 individually tuned Caffeine caches**. Caffeine
uses W-TinyLFU eviction: near-optimal hit rate, O(1) reads, JVM heap only, zero network latency.

| Group | Caches | Max size | TTL |
|---|---|---|---|
| Entity lists | `categories:all`, `audios:all`, `images:all`, `videos:all`, `texts:all`, `projects:all`, `persons:all` | 1 | 10 min |
| Autocomplete | `tags:suggest`, `keywords:suggest` | 1 000 | 10 min |
| Analytics | `analytics:user.v2`, `analytics:overview.v2`, `analytics:users.v2` | 200 / 50 / 50 | 5 min |
| Auth | `users:details` | 500 | 1 min |
| Trending | `trending:results`, `trending:snapshot` | 1 | 5 min |

The sizing is intentional, not arbitrary. The `*:all` caches hold exactly **one** entry — the whole
active list — so `maximumSize=1` is correct and eviction never fires; the TTL is what keeps data fresh
after a mutation. `users:details` is capped at **1 minute** specifically so a permission grant takes
effect quickly rather than lingering for ten.

Choosing Caffeine over Redis has a real consequence: **the cache is per-instance**. One JVM is fine.
Scale to two and each keeps its own copy, so a write on instance A leaves instance B stale until its
TTL expires. That is acceptable for a back-office archive with short TTLs; it would not be for a
high-traffic public site — which is exactly why the sister KHI website backend uses Redis instead.

---

## 7. Security and identity

**Spring Security** with `@EnableWebSecurity` and `@EnableMethodSecurity`. `SecurityConfig` is
deliberately short — four path rules and nothing else:

```
OPTIONS /**                                        permitAll   (preflight)
/api/auth/{register,register-with-image,login}      permitAll
/api/guest/**                                       permitAll   (every method)
/api/**                                             authenticated
anyRequest()                                        authenticated
```

Everything finer lives on the controller methods as `@PreAuthorize`, against four roles — `GUEST`,
`EMPLOYEE`, `TEACHER`, `ADMIN` — and 66 `<resource>:<action>` permissions. `EMPLOYEE` carries no
baseline authorities; a default set is copied into the user's `extraPermissions` when the role is
first assigned, and an admin edits it per user from there. `ADMIN`'s set is locked. Full matrix in
[`internal/02-authorization.md`](./internal/02-authorization.md).

- `SessionCreationPolicy.STATELESS`. The comment in `SecurityConfig` records why: without it,
  `SecurityContextPersistenceFilter` caches the first request's `Authentication` for the life of the
  session, so a role change only took effect after logout and login. It is still true that authorities
  are baked into the **token**, so a new grant needs a new login.
- `JwtAuthenticationEntryPoint` (401) and `JwtAccessDeniedHandler` (403) return the same JSON envelope
  as every other error, with a stable `error` code. Clients branch on that code, never on the status
  alone.
- CSRF disabled — correct for a token-authenticated API.
- Passwords: `BCryptPasswordEncoder` behind a `DaoAuthenticationProvider`.
- Tokens: HS256 via `com.auth0:java-jwt` in `JwtTokenProvider`; `JwtCookieService` mirrors the token
  into the `khi_auth_token` cookie, every attribute driven by `JWT_COOKIE_*` env vars.

> **Two JWT libraries are declared; only one is used.** `io.jsonwebtoken:jjwt-{api,impl,jackson}`
> 0.12.3 is in `pom.xml`, but an import audit of `src/main` and `src/test` finds **zero** references
> to `io.jsonwebtoken`. See §14.

---

## 8. Media — storage, extraction, delivery

### Storage

**AWS SDK for Java v2** (`software.amazon.awssdk:s3` 2.20.30), used across 6 classes. Unlike the
sister project, credentials are read **explicitly** from configuration rather than the SDK's default
provider chain, and every coordinate is env-driven:

```yaml
aws.credentials.access-key: ${AWS_ACCESS_KEY_ID}
aws.credentials.secret-key: ${AWS_SECRET_ACCESS_KEY}
aws.s3.region:        ${AWS_REGION:us-east-1}
aws.s3.bucket:        ${AWS_S3_BUCKET:khi-archive-platform}
aws.s3.base-folder:   ${AWS_S3_BASE_FOLDER:khi-archive-platform-folders}
aws.s3.person-folder: ${AWS_S3_PERSON_FOLDER:persons}
```

### Delivery — the byte proxy

**No S3 URL is ever returned to a browser.** Media fields hold application-relative paths such as
`/api/guest/audio/AUD-001/stream`, and `AudioStreamAPI`, `VideoStreamAPI`, `ImageStreamAPI`,
`TextStreamAPI` and `MaqamStreamAPI` proxy the bytes themselves — handling `Range` requests and
answering `206 Partial Content` so `<audio>` and `<video>` can seek, with ETags and cache headers.

This is the architectural decision that most distinguishes this backend from the KHI website backend,
and it buys three things: access control stays in the application (`removedAt IS NULL`, visibility
checks) rather than in a bucket policy; the storage provider can change without touching a single
stored URL; and no permanent, shareable link to an archive object escapes. It costs bandwidth and CPU
on the API instance — every byte is served twice.

### Extraction — pure Java, no ffprobe

`MediaDurationExtractor` is a **fallback**. The primary duration source is the browser: the frontend's
`media-metadata.js` reads it from a hidden `<audio>`/`<video>` element's `loadedmetadata` event and
sends it in the create/update payload. The server-side path runs only when a client did not supply one
— a non-browser API client, or a codec the browser could not probe.

- **`metadata-extractor` 2.19.0** reads the container-level duration atom for MP4/QuickTime/WAV.
- **`mp3agic` 0.9.1** decodes MP3 frame headers, because metadata-extractor has no MP3 duration tag.
- Formats neither library understands (ogg, flac, wma, avi) return empty, and callers leave the
  duration untouched rather than failing the upload.

Choosing two small pure-Java libraries over ffprobe means **no native binary in the container** — no
install step, no PATH assumptions, no ffmpeg CVE feed to track. The price is the format gaps above.

### Spreadsheets

**Apache POI 5.3.0** (`poi` + `poi-ooxml`) powers `PhysicalMediaExcelImportService`, which imports the
physical-artefact inventory from `.xlsx` with header-name resolution and dedupe. See
[`internal/specialised/physical-media.md`](./internal/specialised/physical-media.md).

---

## 9. API documentation

**There is no Swagger UI in this project** — `springdoc-openapi` is not a dependency, unlike the KHI
website backend, which serves `/swagger-ui.html`. The API reference is this `docs/` tree:
[`external/`](./external/README.md) for the anonymous surface,
[`internal/`](./internal/README.md) for the staff surface, with
[`external/09-recipes.md`](./external/09-recipes.md) providing runnable `curl` walkthroughs.

That is a legitimate choice — hand-written docs explain *why* in a way generated ones cannot — but it
means there is no machine-readable contract, so no generated client and no schema diffing in CI. If
that becomes a problem, adding `springdoc-openapi-starter-webmvc-ui` is a small change; the sister
repo's `OpenApiConfig` is a working template.

---

## 10. Observability and async work

**Actuator** is on the classpath. There is no `management:` block in `application.yaml`, and
`SecurityConfig` sends everything not explicitly permitted to `authenticated()`.

**`AsyncConfig`** enables `@EnableAsync` and `@EnableScheduling`, and defines a bounded
`trendingLogExecutor` — core 2, max 4, queue 1 000 — used exclusively for fire-and-forget interaction
and search logging. Bursts beyond the queue are **silently discarded**, on the stated principle that
losing a few log events is acceptable but blocking an HTTP response is not. If trending counts ever
look low under load, this is why.

**Trace IDs.** The console pattern is
`"%d{yyyy-MM-dd HH:mm:ss} [%X{traceId}] %-5level %logger{36} - %msg%n"`, and `ApiErrorResponses` reads
a correlation id from the MDC keys `traceId` / `trace_id` / `X-Trace-Id` / `requestId` to attach to
error responses.

> **Gap worth fixing:** nothing ever calls `MDC.put`. There is no trace filter in this project and no
> Micrometer tracing bridge on the classpath, so `%X{traceId}` prints empty on every line and the
> `traceId` field in error responses is always null. A ~20-line `OncePerRequestFilter` that accepts an
> inbound `X-Trace-Id`, generates a UUID otherwise, puts it in the MDC and clears it in a `finally`
> would make both work. The sister KHI backend has exactly that filter and can be copied. (It has the
> mirror-image problem: the filter exists, but its log pattern omits the field.)

---

## 11. Configuration, secrets and seed data

**`spring-dotenv` 4.0.0** makes a root `.env` readable as Spring properties. None is committed and the
IntelliJ run configuration defines no environment block, so a local `.env` is what you create —
template in [`FRONTEND_INTEGRATION.md`](./FRONTEND_INTEGRATION.md) §6.

No defaults, startup fails without them: `PGHOST`, `PGPORT`, `PGDATABASE`, `PGUSER`, `PGPASSWORD`,
`JWT_SECRET`, `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`.
With defaults: `JWT_EXPIRATION_MS` (3 days), all `JWT_COOKIE_*`, `AWS_REGION`, `AWS_S3_*`,
`CORS_ALLOWED_ORIGINS` (empty), `PORT` (8080), `APP_SEED_LOAD`, `APP_SEED_DIR`.

**`SeedDataLoader`** is a `CommandLineRunner` gated by `@ConditionalOnProperty("app.seed.load")`. It
reads `seed-data/*.json` in FK order — categories → persons → projects → audios/videos/texts/images —
and is idempotent: records whose business code already exists are skipped, so reruns are safe.

> **`APP_SEED_LOAD` defaults to `true`.** The class Javadoc says the loader is "off by default so it
> never runs in production", but `application.yaml` resolves `app.seed.load: ${APP_SEED_LOAD:true}`.
> With the variable unset it **does** run, on every start, in every environment. It is not destructive,
> but set `APP_SEED_LOAD=false` anywhere you do not want demo rows. The Javadoc and the YAML disagree;
> the YAML wins.

`server.forward-headers-strategy: framework` makes Spring trust Railway's `X-Forwarded-*`.

Alongside the Java, `seed-data/` and `scripts/` hold a small **Python** toolchain — `generate.py`,
`seed_via_rest.py`, `probe_wiki.py`, `generate_test_fixtures.py` — used to build the fixture JSON and
push it through the REST API. A `.venv/` is checked out at the repository root for those.

---

## 12. Testing

7 test classes, run through `spring-boot-starter-{webmvc,data-jpa,security}-test` (JUnit 5, Mockito,
AssertJ, Spring test slices) with **H2** as the test-scope database:

| Class | Covers |
|---|---|
| `KhiArchivePlatformApplicationTests` | context loads |
| `JwtTokenProviderTest`, `JwtCookieServiceTest` | token issue/verify and the cookie contract |
| `ApiExceptionHandlerResponseStatusTest` | the error envelope's status mapping |
| `ProjectReadCacheTest` | the Caffeine read-cache behaviour |
| `GuestSearchServiceFeedTest` | the public grouped feed |
| `S3ServiceTest` | storage service |

This is thin relative to the surface area — 7 test classes against ~232 endpoints, where the sister
KHI backend has 31 against ~171. The highest-value additions, in order: the `@PreAuthorize` matrix
(one test per role per resource would catch an entire class of authorization regression), the `Range`
handling in the five stream proxies, and the POI import's header-resolution and dedupe paths.

---

## 13. Build and repository tooling

- **Maven wrapper** — `./mvnw` committed.
- **`spring-boot-maven-plugin`** builds the executable jar.
- **Lombok 1.18.42**, `provided` scope, registered as an annotation processor.
- **`spring-boot-devtools`** with restart *and* livereload enabled — note the contrast with the sister
  backend, which disables both. Fast local iteration is the priority here.
- **JSpecify 1.0.0** for `@Nullable` / `@NonNull`.

---

## 14. Declared but unused dependencies

An import audit of the entire `src/main` and `src/test` tree found five artifacts in `pom.xml` with
**zero** references anywhere in the source:

| Dependency | Version | Status |
|---|---|---|
| `io.jsonwebtoken:jjwt-api` / `-impl` / `-jackson` | 0.12.3 | Superseded by `com.auth0:java-jwt`. Three artifacts, no usages. |
| `com.google.guava:guava` | 32.1.3-jre | No `com.google.common` import anywhere. |
| `org.apache.commons:commons-lang3` | 3.18.0 | No `org.apache.commons.lang3` import anywhere. |

The same four unused dependencies appear in the KHI website backend's `pom.xml`, which suggests both
poms grew from a shared starting point rather than from actual need.

Worth acting on: guava and commons-lang3 are large, and jjwt is a *cryptographic* library — an unused
crypto dependency still shows up in every CVE scan and still has to be triaged by a person. Drop the
three jjwt artifacts first, then guava and commons-lang3, running the test suite after each removal.

---

## 15. The frontend this backend serves

`khi_archive_platform_frontend` — full integration detail in
[`FRONTEND_INTEGRATION.md`](./FRONTEND_INTEGRATION.md).

| Area | Technology |
|---|---|
| Build | Vite 8 + `@vitejs/plugin-react` |
| Framework | React 19.2 · react-router-dom 7 (SPA, `createBrowserRouter`) |
| Language | JavaScript (ESM) with `jsconfig.json` path aliases — **not** TypeScript |
| HTTP | axios 1.15, one shared `apiClient` with request/response interceptors |
| State | React state and context (`toast-context`, `appearance-context`) — no Redux, no TanStack Query |
| Styling | Tailwind CSS 4 via `@tailwindcss/vite` · `class-variance-authority` · `tailwind-merge` · `tw-animate-css` |
| UI | Base UI (`@base-ui/react`) · shadcn · `lucide-react` icons · Geist variable font |
| Deep-zoom images | OpenSeadragon 6 — tiled viewing of high-resolution scans |
| Documents | pdf.js (`pdfjs-dist` 6) for PDFs, `mammoth` for DOCX |
| Streaming | hls.js 1.6 for adaptive playback |
| Sanitization | DOMPurify 3 |
| Tooling | ESLint 9 + react-hooks/react-refresh plugins |
| Deploy | Vercel — `vercel.json` rewrites `/(.*)` → `/index.html` so client routes survive a refresh |

The two notable choices: **plain JavaScript rather than TypeScript** (the sister KHI frontends are both
TypeScript), and **no data-fetching library** — each `src/services/*.js` module calls `apiClient`
directly and components manage their own loading state. Both are workable at this size; both are the
first things that would need revisiting as the app grows.

---

## See also

- [`FRONTEND_INTEGRATION.md`](./FRONTEND_INTEGRATION.md) — how these technologies meet the SPA
- [`internal/02-authorization.md`](./internal/02-authorization.md) — the roles and the 66 permissions
- [`external/07-streaming.md`](./external/07-streaming.md) — the byte proxies and `Range` handling
- [`internal/specialised/physical-media.md`](./internal/specialised/physical-media.md) — the POI import
- `../CHANGELOG.md` — release history
