# Configuration and Environment

> **Audience:** Backend developers and operators ·
> **Source:** `src/main/resources/application.yaml`, `pom.xml`,
> `user/configs/JwtCookieProperties.java`, `user/configs/AppCorsProperties.java`,
> `user/configs/AppConfig.java`, `user/configs/SecurityConfig.java`,
> `platform/config/WebConfig.java`, `platform/config/CacheConfig.java`,
> `platform/config/JacksonConfig.java`, `S3Config.java`, `S3Service.java`,
> `platform/seed/SeedDataLoader.java`, `.gitignore`

This is the complete configuration reference for `khi_archive_platform_backend`. Every setting the
application reads lives in exactly one of three places: `src/main/resources/application.yaml`,
an environment variable that the YAML interpolates with `${...}`, or a `@Value` default baked into
a Java class. This document lists all of them, walks the YAML file section by section, flags the
two settings that break a running deployment when changed, and ends with how to bring the app up
locally.

There is a single `application.yaml`. There are **no** profile-specific files
(`application-dev.yaml`, `application-prod.yaml`) in the repository — every environment difference
is expressed through environment variables.

---

## Where configuration comes from

| Layer | Mechanism | Wins over |
|---|---|---|
| Environment variables / `.env` | `${VAR}` and `${VAR:default}` interpolation in `application.yaml`; `.env` is loaded by `me.paulschwarz:spring-dotenv:4.0.0` (`pom.xml`) | YAML literals for any key written as `${...}` |
| `application.yaml` | Packaged at `src/main/resources/application.yaml` | Java `@Value` / `@ConfigurationProperties` field defaults |
| Java defaults | `@Value("${key:default}")` and initialized fields on `@ConfigurationProperties` beans | nothing — last resort |

A variable with **no** `:default` in the YAML (for example `${PGHOST}`) is mandatory. If it is
absent the context fails to start with an unresolved-placeholder error before any HTTP port opens.

---

## Environment variable reference

Every `${...}` placeholder in `application.yaml`. Twenty-two variables; eight are mandatory.

| Variable | Required | Default | Controls | Example |
|---|---|---|---|---|
| `PGHOST` | **Yes** | — | Host segment of `spring.datasource.url` (`jdbc:postgresql://${PGHOST}:${PGPORT}/${PGDATABASE}`) | `localhost` |
| `PGPORT` | **Yes** | — | Port segment of the JDBC URL | `5432` |
| `PGDATABASE` | **Yes** | — | Database name segment of the JDBC URL | `khi_archive` |
| `PGUSER` | **Yes** | — | `spring.datasource.username` | `khi_app` |
| `PGPASSWORD` | **Yes** | — | `spring.datasource.password` | `change-me-locally` |
| `JWT_SECRET` | **Yes** | — | HMAC256 signing key for every issued token (`JwtTokenProvider.secret`, used as `Algorithm.HMAC256(secret)`) | a long random string, 32+ chars |
| `JWT_EXPIRATION_MS` | No | `259200000` (3 days) | Token lifetime **and** cookie `Max-Age`. `JwtCookieService.tokenLifetimeSeconds()` derives the cookie age from this same value with `Math.max(1L, Math.ceilDiv(expirationMs, 1_000L))`, so the two can never drift | `259200000` |
| `JWT_COOKIE_NAME` | No | `khi_auth_token` | Name of the auth cookie written by `JwtCookieService.addAuthCookie` and read by `resolveToken` | `khi_auth_token` |
| `JWT_COOKIE_SECURE` | No | `true` | `Secure` flag on the auth cookie. Must be `false` for plain-HTTP `localhost` browser testing | `false` |
| `JWT_COOKIE_HTTP_ONLY` | No | `true` | `HttpOnly` flag. Leave `true`; JavaScript must never read the token | `true` |
| `JWT_COOKIE_SAME_SITE` | No | `None` | `SameSite` attribute. `None` is required for a cross-origin SPA and only valid together with `Secure=true` | `None` |
| `JWT_COOKIE_PATH` | No | `/` | `Path` attribute of the auth cookie | `/` |
| `CORS_ALLOWED_ORIGINS` | No | `` (empty) | Comma-separated extra origins, **merged on top of** the hardcoded `AppCorsProperties.ALWAYS_ALLOWED_ORIGINS` list (see [CORS](#appcors)) | `https://staging.example.test,http://localhost:4200` |
| `APP_SEED_LOAD` | No | `true` | Whether `SeedDataLoader` runs. It is a `@ConditionalOnProperty(name = "app.seed.load", havingValue = "true")` `CommandLineRunner` | `false` |
| `APP_SEED_DIR` | No | `./seed-data` | Directory the seed loader reads `categories.json`, `persons.json`, `projects.json`, `audios.json`, `videos.json`, `texts.json`, `images.json` from | `./seed-data` |
| `AWS_ACCESS_KEY_ID` | **Yes** | — | `aws.credentials.access-key`, passed to `AwsBasicCredentials.create(...)` in `S3Config` | `AKIAEXAMPLE...` |
| `AWS_SECRET_ACCESS_KEY` | **Yes** | — | `aws.credentials.secret-key`, the other half of the static credentials pair | `wJalrEXAMPLE...` |
| `AWS_REGION` | No | `us-east-1` | `Region.of(region)` on the `S3Client` builder, and `S3Service.region` | `us-east-1` |
| `AWS_S3_BUCKET` | No | `khi-archive-platform` | `S3Service.bucket` — target bucket for every `PutObject` / `GetObject` | `khi-archive-platform` |
| `AWS_S3_BASE_FOLDER` | No | `khi-archive-platform-folders` | `S3Service.baseFolder` — key prefix under which all media folders are created | `khi-archive-platform-folders` |
| `AWS_S3_PERSON_FOLDER` | No | `persons` | `S3Service.personFolder` — sub-folder for person portrait objects | `persons` |
| `PORT` | No | `8080` | `server.port` | `8080` |

**Credentials are static, not role-based.** `S3Config` builds the `S3Client` with
`StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey))`. The default
AWS provider chain (instance profile, SSO, `~/.aws/credentials`) is **not** consulted — both
variables must be set even on an EC2 instance with an attached role.

### Config keys read by Java but absent from `application.yaml`

These two keys are never declared in the YAML, so their `@Value` defaults always apply unless you
add them yourself or export them as `SPRING_APPLICATION_JSON` / `--app.x.y` overrides.

| Key | Default | Read by | Controls |
|---|---|---|---|
| `app.upload.dir` | `uploads/profile-images` | `user/service/UserService.java` | Local filesystem directory for profile-image writes (`Paths.get(uploadDir)`) |
| `app.email.verify-mx` | `true` | `user/service/UserValidator.java` | Whether the DNS MX (fallback A/AAAA) check runs during email validation. The check is gated on `verifyMx && role == Role.GUEST`, so it only affects public self-signups — admin-provisioned EMPLOYEE/ADMIN accounts skip it either way. Its javadoc says: "disable in tests or air-gapped environments by setting `app.email.verify-mx=false`" |

---

## The `.env` file

`me.paulschwarz:spring-dotenv:4.0.0` is on the compile classpath (`pom.xml`), so a `.env` file at
the **project root** is read at startup and its entries become resolvable as `${...}` placeholders
in `application.yaml`. This is the normal way to run the app locally — no shell exports needed.

> **Warning — `.env` is not currently ignored by Git.**
> The repository `.gitignore` covers `target/`, IDE folders and build output. It contains **no**
> `.env` entry, and no `.env` file exists in the repo today. A `.env` written at the project root
> is therefore *tracked by default* and a stray `git add .` will commit your database password,
> `JWT_SECRET` and AWS secret key into history. Add `.env` to `.gitignore` before you create one,
> and never commit it. Commit `.env.example` with placeholders instead.

### `.env.example`

```dotenv
# ─── PostgreSQL (all five are mandatory) ──────────────────────────────────────
PGHOST=localhost
PGPORT=5432
PGDATABASE=khi_archive
PGUSER=khi_app
PGPASSWORD=replace-me

# ─── JWT (JWT_SECRET is mandatory) ────────────────────────────────────────────
# Rotating JWT_SECRET invalidates every token already issued. See the warning below.
JWT_SECRET=replace-with-a-long-random-string-at-least-32-characters
JWT_EXPIRATION_MS=259200000
JWT_COOKIE_NAME=khi_auth_token
# Set JWT_COOKIE_SECURE=false and JWT_COOKIE_SAME_SITE=Lax for plain-HTTP localhost testing.
JWT_COOKIE_SECURE=true
JWT_COOKIE_HTTP_ONLY=true
JWT_COOKIE_SAME_SITE=None
JWT_COOKIE_PATH=/

# ─── CORS ─────────────────────────────────────────────────────────────────────
# Comma-separated. Merged on top of the always-allowed list in AppCorsProperties.
CORS_ALLOWED_ORIGINS=

# ─── Seed data ────────────────────────────────────────────────────────────────
APP_SEED_LOAD=false
APP_SEED_DIR=./seed-data

# ─── AWS S3 (both credentials are mandatory) ──────────────────────────────────
AWS_ACCESS_KEY_ID=replace-me
AWS_SECRET_ACCESS_KEY=replace-me
AWS_REGION=us-east-1
AWS_S3_BUCKET=khi-archive-platform
AWS_S3_BASE_FOLDER=khi-archive-platform-folders
AWS_S3_PERSON_FOLDER=persons

# ─── Server ───────────────────────────────────────────────────────────────────
PORT=8080
```

---

## Settings that break a running deployment

> **Danger — changing either of these on a live deployment logs every user out.**
>
> **1. `JWT_SECRET`**
> Tokens are signed with `Algorithm.HMAC256(secret)` in
> `JwtTokenProvider.generateToken`. The same secret verifies them on every request. Rotating the
> value means every token signed by the previous deployment fails verification immediately — every
> signed-in browser is rejected on its next call. `application.yaml` carries this comment on the
> key itself:
>
> > `# Keep this value unchanged between deployments. Rotating it immediately`
> > `# invalidates every JWT signed by the previous deployment.`
>
> Rotate it only during a planned window, and expect all users to re-authenticate.
>
> **2. `spring.jpa.hibernate.ddl-auto`**
> The committed value is `update`. Setting it to `create` or `create-drop` drops and recreates
> every table on boot. Authentication revocation is backed by the `sessions` table
> (`@Table(name = "sessions")` on `user/model/Session.java`) — `JwtTokenProvider.generateToken`
> writes one row per login and stamps its `sessionId` into the token, and `JWTAuthenticationFilter`
> then calls `TokenService.isTokenBlacklisted` on every request, which treats a missing, inactive or
> expired session row as a revoked token (fronted by a 2-minute in-process validity cache). Wiping
> that table invalidates every active login even though the tokens themselves are still
> cryptographically valid. `users_tbl`,
> `token_blacklist` and all content tables go with it. The YAML says this in place:
>
> > `# Authentication revocation is backed by the sessions table. A`
> > `# create/create-drop schema mode deletes every active login on restart.`
>
> There is no Flyway or Liquibase in this project — a wiped schema cannot be replayed from
> migrations. Leave `ddl-auto` at `update` outside of a throwaway local database.

---

## `application.yaml` walkthrough

### `spring.application`

```yaml
spring:
  application:
    name: khi_archive_platform_backend
```

Used for logging and actuator identity only.

### `spring.datasource` — PostgreSQL

```yaml
  datasource:
    url: jdbc:postgresql://${PGHOST}:${PGPORT}/${PGDATABASE}
    username: ${PGUSER}
    password: ${PGPASSWORD}
    driver-class-name: org.postgresql.Driver
```

The driver (`org.postgresql:postgresql`) is a `runtime`-scope dependency. No connection-pool
settings are declared, so HikariCP runs with Spring Boot's defaults — pool size, timeouts and
leak detection are `_Not documented in source._`

### `spring.jpa` — JPA / Hibernate

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
          dialect: org.hibernate.dialect.PostgreSQLDialect
          default_batch_fetch_size: 1000
    jdbc:
      time_zone: UTC
      batch_size: 100
      order_inserts: true
      order_updates: true
```

Only three of these keys take effect:

| Key | Value | Effect |
|---|---|---|
| `open-in-view` | `false` | No `OpenEntityManagerInViewInterceptor`. Lazy associations must be initialized inside the service transaction or they throw `LazyInitializationException` during serialization |
| `show-sql` | `true` | Hibernate echoes SQL to the log. Noisy — a candidate to turn off in production |
| `hibernate.ddl-auto` | `update` | Hibernate adds missing tables and columns on boot. It never drops or narrows anything, and it never refreshes an existing `CHECK` constraint — which is why the initializer beans exist (see [Startup work](#startup-work)) |

#### The other eight keys are inert — verified

`properties:` sits *under* `hibernate:`, so the literal path is
`spring.jpa.hibernate.properties.hibernate.*`; the `jdbc:` block sits directly under `jpa:`, giving
`spring.jpa.jdbc.*`. **Neither path exists.** Verified against
`META-INF/spring-configuration-metadata.json` in `spring-boot-jpa-4.0.5.jar` and
`spring-boot-hibernate-4.0.5.jar`: the only keys defined under `spring.jpa` are `database`,
`database-platform`, `generate-ddl`, `mapping-resources`, `open-in-view`, `properties`, `show-sql`,
`hibernate.ddl-auto`, `hibernate.naming.*` and `hibernate.use-new-id-generator-mappings`. There is
no `spring.jpa.hibernate.properties` and no `spring.jpa.jdbc`. Unknown keys under a
`@ConfigurationProperties` type are dropped silently instead of failing the boot, which is why this
survived unnoticed.

These eight values never reach Hibernate:

| Inert key | Declared value |
|---|---|
| `spring.jpa.hibernate.properties.hibernate.format_sql` | `true` |
| `spring.jpa.hibernate.properties.hibernate.use_sql_comments` | `true` |
| `spring.jpa.hibernate.properties.hibernate.dialect` | `org.hibernate.dialect.PostgreSQLDialect` |
| `spring.jpa.hibernate.properties.hibernate.default_batch_fetch_size` | `1000` |
| `spring.jpa.jdbc.time_zone` | `UTC` |
| `spring.jpa.jdbc.batch_size` | `100` |
| `spring.jpa.jdbc.order_inserts` | `true` |
| `spring.jpa.jdbc.order_updates` | `true` |

**This is a live defect in the application, not a documentation quirk.** What it costs today:

- **No batch fetching.** With `default_batch_fetch_size` inert, the ~26 `@ElementCollection` tables
  are loaded one `SELECT` per parent row per association. Every list endpoint that misses its read
  cache pays N+1 — a full active-list load of 1000 projects across five lazy associations is ~5000
  round trips instead of ~5.
- **No JDBC insert/update batching.** The bulk-create endpoints and the ~4,400-row `.xlsx`
  physical-media import send one round trip per statement. `order_inserts` / `order_updates` are
  moot while there is no batch to fill.
- **Timestamps bind in the JVM default zone, not UTC.** `jdbc.time_zone: UTC` is inert, so
  Hibernate binds `Instant` values using `TimeZone.getDefault()` on whichever host is running.
  Serialization is unaffected — that is `spring.jackson.time-zone`, which does work.
- **SQL logs are unformatted and uncommented.** `format_sql` and `use_sql_comments` are inert.
  Statements still appear, but via `logging.level.org.hibernate.SQL: DEBUG`, and they arrive as
  single unformatted lines with no JPQL/HQL comment prefix.
- `dialect` being inert is the one harmless case: Hibernate auto-detects `PostgreSQLDialect` from
  the JDBC connection metadata and lands on the same value.

**Current YAML** (what is in the file):

```yaml
  jpa:
    open-in-view: false
    show-sql: true
    hibernate:
      ddl-auto: update
      properties:                                        # ← inert from here down
        hibernate:
          format_sql: true
          use_sql_comments: true
          dialect: org.hibernate.dialect.PostgreSQLDialect
          default_batch_fetch_size: 1000
    jdbc:                                                # ← inert block
      time_zone: UTC
      batch_size: 100
      order_inserts: true
      order_updates: true
```

**Corrected YAML** (the paths Spring Boot actually binds):

```yaml
  jpa:
    open-in-view: false
    show-sql: true
    hibernate:
      ddl-auto: update
    properties:            # sibling of hibernate:, not a child
      hibernate:
        format_sql: true
        use_sql_comments: true
        default_batch_fetch_size: 1000
        # dialect: dropped — Hibernate auto-detects PostgreSQLDialect
        jdbc:
          time_zone: UTC
          batch_size: 100
        order_inserts: true
        order_updates: true
```

Note that `order_inserts` / `order_updates` are `hibernate.order_inserts` /
`hibernate.order_updates` — siblings of the `jdbc:` block, not members of it. Only `time_zone` and
`batch_size` go inside `jdbc:`.

See [Discrepancies observed in source](#discrepancies-observed-in-source) and
[`../database/indexes-and-performance.md`](../database/indexes-and-performance.md#eight-hibernate-keys-are-inert-verified).

### `spring.web` and `spring.messages` — locale and i18n

```yaml
  web:
    locale: ckb
    locale-resolver: fixed
    error:
      include-message: always
      include-binding-errors: always
      include-stacktrace: never
  messages:
    basename: i18n/messages
    encoding: UTF-8
    fallback-to-system-locale: false
  mvc:
    format:
      date: yyyy-MM-dd
      date-time: yyyy-MM-dd HH:mm:ss
```

| Key | Value | Effect |
|---|---|---|
| `web.locale` | `ckb` | Central Kurdish (Sorani) as the application locale |
| `web.locale-resolver` | `fixed` | A `FixedLocaleResolver` — the locale is constant. `Accept-Language` from the browser is ignored entirely, so responses never vary by client language |
| `messages.basename` | `i18n/messages` | Resource-bundle basename for `MessageSource` lookups |
| `messages.encoding` | `UTF-8` | Bundle file encoding |
| `messages.fallback-to-system-locale` | `false` | Never falls back to the JVM's system locale when a bundle for the requested locale is missing |
| `mvc.format.date` | `yyyy-MM-dd` | Binding/format pattern for `LocalDate` request parameters and fields |
| `mvc.format.date-time` | `yyyy-MM-dd HH:mm:ss` | Same for `LocalDateTime` |

**No message bundle is present.** There is no `i18n/` directory and no `messages*.properties` file
anywhere under `src/main/resources` — the basename is configured ahead of the bundle it points at.

The three `error.*` keys are written under `spring.web.error`; Spring Boot's error-response
properties are canonically `server.error.include-message`, `server.error.include-binding-errors`
and `server.error.include-stacktrace`. See
[Discrepancies observed in source](#discrepancies-observed-in-source).

### `spring.jackson` — JSON serialization

```yaml
  jackson:
    serialization:
      indent-output: true
    default-property-inclusion: non_null
    time-zone: Asia/Baghdad
```

| Key | Value | Effect |
|---|---|---|
| `serialization.indent-output` | `true` | Pretty-printed JSON on the wire. Costs bytes; useful while the API is being explored by hand |
| `default-property-inclusion` | `non_null` | **Null fields are omitted from every response.** Absence of a key means `null`, not "missing feature" — this is why response examples across the docs show optional fields only when populated |
| `time-zone` | `Asia/Baghdad` | Timestamps serialize in Baghdad local time |

All three take effect on responses. They are read by the Jackson 3 mapper that Spring Boot 4
auto-configures for HTTP message conversion.

#### Two Jackson majors are on the classpath

`./mvnw -o dependency:tree` resolves both:

```
org.springframework.boot:spring-boot-starter-jackson:4.0.5
  └── tools.jackson.core:jackson-databind:3.1.0        (Jackson 3 — serializes responses)
com.fasterxml.jackson.core:jackson-databind:2.21.2      (Jackson 2 — declared directly)
```

Spring Boot 4 auto-configures the **Jackson 3** mapper (`tools.jackson.*`) for HTTP message
conversion, and that is the mapper that reads `spring.jackson.*`. The response behaviors in the
table above therefore hold.

`platform/config/JacksonConfig.java` declares a **Jackson 2** bean —
`com.fasterxml.jackson.databind.ObjectMapper`, built with a bare `new ObjectMapper()`, registering
`JavaTimeModule` and disabling `SerializationFeature.WRITE_DATES_AS_TIMESTAMPS`:

```java
@Bean
public ObjectMapper objectMapper() {
    ObjectMapper mapper = new ObjectMapper();       // com.fasterxml.jackson.databind
    mapper.registerModule(new JavaTimeModule());
    mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    return mapper;
}
```

**That bean is not the response mapper.** It is a different Jackson major from the one wired into
the message converters, so nothing returned from a controller passes through it. It has two
consumers:

1. **The multipart controllers** — `AudioAPI`, `VideoAPI`, `ImageAPI`, `TextAPI`, `PersonAPI` and
   `MaqamAPI` inject it to `readValue` the `data` JSON part of a `multipart/form-data` upload into
   its DTO. Inbound only.
2. **The three security error writers** — `JWTAuthenticationFilter`, `JwtAuthenticationEntryPoint`
   and `JwtAccessDeniedHandler` call `objectMapper.writeValue(response.getWriter(), payload)`
   directly, short-circuiting the message converters entirely.

Because the bean is a bare `new ObjectMapper()` rather than one built through Spring's builder, it
honors **none** of the `spring.jackson.*` settings above. For the inbound `data` part that does not
matter. For the security error bodies it does: the `401`/`403` JSON those three classes write is
**not** pretty-printed and does not use the `Asia/Baghdad` context zone, unlike every response that
goes through a controller. Null omission survives only because `ApiErrorResponse` carries its own
`@JsonInclude(JsonInclude.Include.NON_NULL)` — it does not come from
`spring.jackson.default-property-inclusion`. `jackson-datatype-jsr310` 2.21.0 is pinned explicitly
in `pom.xml` to match the Jackson 2 line, which is what keeps timestamps in those bodies ISO-8601
rather than epoch numbers.

### `spring.devtools`

```yaml
  devtools:
    restart:
      enabled: true
    livereload:
      enabled: true
```

`spring-boot-devtools` is declared `runtime` + `optional` in `pom.xml`, and the
`spring-boot-maven-plugin` repackage excludes only Lombok — devtools is excluded from a fat jar by
Boot's own convention. These keys therefore matter when running from an IDE or `spring-boot:run`
and are inert for a packaged jar.

### `spring.servlet.multipart` — upload limits

```yaml
  servlet:
    multipart:
      enabled: true
      max-file-size: 5GB
      # A 5 GB file plus the JSON part and multipart boundaries must fit.
      max-request-size: 6GB
      file-size-threshold: 2MB
```

| Key | Value | Effect |
|---|---|---|
| `enabled` | `true` | Multipart resolution is on; without it `MultipartFile` parameters never bind |
| `max-file-size` | `5GB` | Largest single uploaded part. Exceeding it yields `MaxUploadSizeExceededException` |
| `max-request-size` | `6GB` | Largest whole multipart request — the media file plus the `data` JSON part plus boundaries |
| `file-size-threshold` | `2MB` | Parts above 2 MB spool to a temp file instead of staying in heap |

These are the effective ceiling for media uploads; the Tomcat connector limits below are
deliberately unbounded so this layer is the one that enforces the cap.

### `spring.cache`

```yaml
  cache:
    type: caffeine
```

Caffeine, in-process, on the JVM heap — **not** Redis. Note that
`platform/config/CacheConfig.java` declares an explicit `CacheManager` bean (a `SimpleCacheManager`
holding hand-built `CaffeineCache` instances), so the cache **names, sizes and TTLs come from that
class**, not from any `spring.cache.caffeine.spec` property. A `@Cacheable` referring to a name not
listed in `CacheConfig` will fail at runtime rather than lazily creating a cache. The registered
names are `categories:all`, `audios:all`, `images:all`, `videos:all`, `texts:all`, `projects:all`,
`persons:all`, `tags:suggest`, `keywords:suggest`, `analytics:user.v2`, `analytics:overview.v2`,
`analytics:users.v2`, `users:details`, `trending:results`, `trending:snapshot`.

### `jwt`

```yaml
jwt:
  secret: ${JWT_SECRET}
  expiration-ms: ${JWT_EXPIRATION_MS:259200000}
  cookie-name: ${JWT_COOKIE_NAME:khi_auth_token}
  cookie-secure: ${JWT_COOKIE_SECURE:true}
  cookie-http-only: ${JWT_COOKIE_HTTP_ONLY:true}
  cookie-same-site: ${JWT_COOKIE_SAME_SITE:None}
  cookie-path: ${JWT_COOKIE_PATH:/}
```

Bound to `user/configs/JwtCookieProperties.java` (`@ConfigurationProperties(prefix = "jwt")`),
except `jwt.secret` which is injected directly into `JwtTokenProvider` via
`@Value("${jwt.secret}")`. `JwtCookieService` builds the cookie from these properties:

```java
ResponseCookie.ResponseCookieBuilder builder = ResponseCookie.from(properties.getCookieName(), token)
        .httpOnly(properties.isCookieHttpOnly())
        .secure(properties.isCookieSecure())
        .path(properties.getCookiePath())
        .maxAge(maxAgeSeconds);

String sameSite = properties.getCookieSameSite();
if (sameSite != null && !sameSite.isBlank()) {
    builder.sameSite(sameSite);
}
```

`tokenLifetimeSeconds()` throws `IllegalStateException("jwt.expiration-ms must be greater than
zero")` if the value is `<= 0`, so a zero or negative `JWT_EXPIRATION_MS` fails at login rather
than issuing a session cookie.

**Browser combination that matters:** `SameSite=None` is only honored alongside `Secure=true`, and
`Secure=true` cookies are dropped over plain HTTP. For `http://localhost` development set
`JWT_COOKIE_SECURE=false` and `JWT_COOKIE_SAME_SITE=Lax`; for the deployed cross-origin SPA keep
the committed defaults (`true` / `None`).

### `app.cors`

```yaml
app:
  cors:
    allowed-origins: ${CORS_ALLOWED_ORIGINS:}
    allowed-methods: GET,POST,PUT,DELETE,OPTIONS,PATCH
    allowed-headers: "*"
    allow-credentials: true
    max-age: 3600
```

Bound to `user/configs/AppCorsProperties.java`. `CORS_ALLOWED_ORIGINS` does not *replace* the
allow-list, it *extends* it — `getAllowedOriginsList()` seeds a `LinkedHashSet` with a hardcoded
constant first:

```java
private static final List<String> ALWAYS_ALLOWED_ORIGINS = List.of(
        "http://localhost:5173",
        "http://localhost:3000",
        "https://khi-archive-platform-frontend.vercel.app",
        "https://khi-archive-platform.s3.us-east-1.amazonaws.com"
);
```

Those four origins are allowed regardless of the environment variable, and cannot be removed
through configuration. All CSV values are split on `,`, trimmed, and blanks dropped.

`platform/config/WebConfig.java` consumes the properties in two layers:

1. A `CorsFilter` bean at `@Order(Ordered.HIGHEST_PRECEDENCE)`, registered for `/**`, running
   **before** Spring Security so that 401/403/500 responses still carry CORS headers and the
   browser shows the real error instead of a generic CORS failure.
2. `addCorsMappings` on `WebMvcConfigurer` as an MVC-level fallback.

Both layers read `getAllowedOriginsList()`, `getAllowedMethodsList()` and `getMaxAge()` from the
properties, but write headers and credentials literally: the filter calls
`config.addAllowedHeader("*")` and `config.setAllowCredentials(true)`, and `addCorsMappings` calls
`.allowedHeaders("*")` and `.allowCredentials(true)`. `app.cors.allowed-headers` and
`app.cors.allow-credentials` are therefore bound onto the properties bean but consulted by neither
layer — changing them in YAML has no effect at all, and `getAllowedHeadersList()` has no caller
anywhere in the codebase.

### `app.seed`

```yaml
  seed:
    load: ${APP_SEED_LOAD:true}
    dir: ${APP_SEED_DIR:./seed-data}
```

`platform/seed/SeedDataLoader.java` is a `CommandLineRunner` gated by
`@ConditionalOnProperty(name = "app.seed.load", havingValue = "true")`. When active it reads JSON
from `app.seed.dir` and inserts in FK-safe order: categories → persons → projects →
audios/videos/texts/images. It is idempotent — a record whose business code already exists is
skipped, so reruns are safe. A missing directory is a warning, not a failure:

```
Seed dir not found: {} — skipping load.
```

The committed default is `true`, while the class javadoc states "Off by default so it never runs
in production." Set `APP_SEED_LOAD=false` explicitly in any non-development environment rather
than relying on the documented intent.

### `aws`

```yaml
aws:
  credentials:
    access-key: ${AWS_ACCESS_KEY_ID}
    secret-key: ${AWS_SECRET_ACCESS_KEY}
  s3:
    region: ${AWS_REGION:us-east-1}
    bucket: ${AWS_S3_BUCKET:khi-archive-platform}
    base-folder: ${AWS_S3_BASE_FOLDER:khi-archive-platform-folders}
    person-folder: ${AWS_S3_PERSON_FOLDER:persons}
```

`S3Config` builds the `S3Client`; `S3Service` reads `bucket`, `base-folder`, `region` and
`person-folder` through `@Value`. Two additional folder names are compile-time constants in
`S3Service`, not configuration: `DEFAULT_FOLDER = "files"` and
`PROFILE_FOLDER = "user_profile_images"`. Multipart uploads use a fixed
`MULTIPART_PART_SIZE = 16 * 1024 * 1024` (16 MB) part size.

Media bytes are proxied through the API rather than linked: `GuestMapper` fills the guest DTOs with
paths like `/api/guest/audio/{code}/stream` and `/api/guest/image/{code}/view`, `ImageService` uses
`/api/image/{code}/view` on the staff DTO, and `MaqamResponseDTO` deliberately omits the entity's
`audioFileUrl` in favour of `streamUrl`. **One exception:** a person portrait is stored as the raw
public object URL that `S3Service.uploadPersonPortrait` returns
(`https://{bucket}.s3.{region}.amazonaws.com/{key}`, built by `S3Service.getPublicUrl`), kept in
`Person.mediaPortrait`, and copied verbatim into `PersonResponseDTO` by `PersonMapper` — so that one
field does reach the browser as an S3 URL.

### `server` — port and Tomcat limits

```yaml
server:
  forward-headers-strategy: framework
  port: ${PORT:8080}
  tomcat:
    # Tomcat stores these connector limits as 32-bit byte counts, so values
    # above 2 GB overflow. Spring's multipart limits above enforce the cap.
    max-swallow-size: -1
    max-http-form-post-size: -1
    max-parameter-count: 10000
```

| Key | Value | Effect |
|---|---|---|
| `forward-headers-strategy` | `framework` | Spring's `ForwardedHeaderFilter` honors `X-Forwarded-Proto` / `-Host` / `-For`, so generated URLs and `request.isSecure()` are correct behind a reverse proxy or load balancer |
| `port` | `${PORT:8080}` | HTTP listen port |
| `tomcat.max-swallow-size` | `-1` | Unlimited — Tomcat will read and discard the body of a rejected request instead of resetting the connection mid-upload |
| `tomcat.max-http-form-post-size` | `-1` | Unlimited at the connector layer |
| `tomcat.max-parameter-count` | `10000` | Cap on request parameters, raised for bulk-create payloads |

The two `-1` values are deliberate: these connector limits are 32-bit byte counts, so a literal
5 GB would overflow. The real ceiling is enforced by `spring.servlet.multipart.*` above.

### `logging`

```yaml
logging:
  pattern:
    console: "%d{yyyy-MM-dd HH:mm:ss} [%X{traceId}] %-5level %logger{36} - %msg%n"
  level:
    root: INFO
    ak.dev.khi_backend: DEBUG
    org.hibernate.SQL: DEBUG
    org.hibernate.orm.jdbc.bind: TRACE
    org.springframework.web.servlet: DEBUG
    org.springframework.security: INFO
    org.springframework.cache: DEBUG
    org.springframework.jdbc: DEBUG
```

The console pattern pulls `traceId` from MDC. No file appender and no rotation policy are
configured — log shipping is `_Not documented in source._`

This level set is development-tuned: `org.hibernate.SQL=DEBUG` combined with
`org.hibernate.orm.jdbc.bind=TRACE` prints every statement **and every bound parameter value**,
which will put personal data and query contents into the log stream. Combined with
`spring.jpa.show-sql: true` the same SQL is emitted twice. Lower both before running against
production data.

The application's own base package is `ak.dev.khi_archive_platform` (see
`KhiArchivePlatformApplication.java`), so the `ak.dev.khi_backend` logger entry does not match any
package in this codebase and raises nothing above `root: INFO`.

---

## Startup work

Because `ddl-auto=update` never refreshes an existing `CHECK` constraint and there is no migration
tool, schema fix-ups run as beans on `ApplicationReadyEvent` using `JdbcTemplate`. They are
idempotent and log-and-continue on failure, so a first boot against an empty database is safe.
They are also the reason the database user needs more than plain DML rights.

`platform/config/MediaSearchIndexInitializer.java` needs the trigram extension:

```java
jdbcTemplate.execute("CREATE EXTENSION IF NOT EXISTS pg_trgm");
```

This one statement is the exception to log-and-continue: if it throws, the initializer logs
`Failed to ensure pg_trgm extension` and `return`s, so that boot skips everything below as well.

It otherwise creates the GIN-trigram and btree indexes, drops the five stale action constraints
(`image_audit_logs_action_check`, `text_audit_logs_action_check`, `video_audit_logs_action_check`,
`audio_audit_logs_action_check`, `project_audit_logs_action_check`) so new enum values can be
persisted, and backfills optimistic-lock versions across the content tables:

```java
jdbcTemplate.update("UPDATE " + table + " SET version = 0 WHERE version IS NULL");
```

over the literal table list `"audios", "videos", "images", "texts", "projects", "person",
"categories"`.

`user/configs/UserRoleConstraintInitializer.java` rebuilds the role constraint from the live
`Role` enum on every boot:

```java
jdbcTemplate.execute("ALTER TABLE users_tbl DROP CONSTRAINT IF EXISTS \"" + name + "\"");
...
jdbcTemplate.execute(
        "ALTER TABLE users_tbl ADD CONSTRAINT users_tbl_role_check " +
        "CHECK (role IN (" + values + "))");
```

after locating existing constraints with:

```sql
SELECT con.conname
FROM pg_constraint con
JOIN pg_class c ON c.oid = con.conrelid
JOIN pg_attribute a
  ON a.attrelid = c.oid AND a.attnum = ANY(con.conkey)
WHERE c.relname = 'users_tbl'
  AND con.contype = 'c'
  AND a.attname = 'role'
```

The same pattern is repeated by `UserAuditActionConstraintInitializer`,
`AnalyticsAuditActionConstraintInitializer`, `MaqamAuditActionConstraintInitializer`,
`GuestCorrectionAuditActionConstraintInitializer`,
`PhysicalMediaAuditActionConstraintInitializer` and
`PhysicalMediaDigitizationConstraintInitializer`. `PhysicalMediaTypeSeeder` inserts missing rows
into `physical_media_types` and leaves existing rows untouched.

**Database privilege requirement:** `CREATE EXTENSION IF NOT EXISTS pg_trgm` and
`ALTER TABLE ... ADD CONSTRAINT` mean `PGUSER` must own the schema and be able to install
extensions. On a managed PostgreSQL service, install `pg_trgm` once as a superuser first; the
initializer then finds it already present and continues.

---

## Discrepancies observed in source

Recorded so they are not mistaken for documentation errors. Each is a plain reading of the files
listed at the top of this page. Item 1 is a confirmed live defect with measurable cost; the rest
are observations, not recommendations to change behavior without testing.

| # | Observation | Where |
|---|---|---|
| 1 | **Live defect.** Eight Hibernate tuning keys are written at `spring.jpa.hibernate.properties.hibernate.*` and `spring.jpa.jdbc.*`. Neither path exists in Boot 4's configuration metadata, so all eight are inert: no batch fetching, no JDBC batching, and timestamp binding in the JVM default zone rather than UTC. See [The other eight keys are inert](#the-other-eight-keys-are-inert--verified) for the corrected YAML | `application.yaml` lines 22–32 |
| 2 | Error-detail keys are written at `spring.web.error.*`; the canonical paths are `server.error.*` | `application.yaml` lines 39–42 |
| 3 | `spring.messages.basename: i18n/messages` points at a bundle that does not exist — there is no `i18n/` directory under `src/main/resources` | `application.yaml` line 44 |
| 4 | `logging.level.ak.dev.khi_backend` does not match the real base package `ak.dev.khi_archive_platform` | `application.yaml` line 140 |
| 5 | `APP_SEED_LOAD` defaults to `true` in YAML while `SeedDataLoader`'s javadoc says "Off by default so it never runs in production" | `application.yaml` line 106 vs `SeedDataLoader.java` |
| 6 | `JWT_EXPIRATION_MS` has three defaults: `259200000` in YAML, `259_200_000L` on the `JwtCookieProperties` field, and `86400000` in `JwtTokenProvider`'s `@Value("${jwt.expiration-ms:86400000}")`. The YAML always supplies a value, so `259200000` is what actually applies | `application.yaml` line 89, `JwtCookieProperties.java`, `JwtTokenProvider.java` |
| 7 | `JwtCookieProperties` field defaults (`cookieSecure = false`, `cookieSameSite = "Strict"`) differ from the YAML defaults (`true`, `None`). The YAML wins | `JwtCookieProperties.java` lines 12–14 |
| 8 | `app.cors.allowed-headers` and `app.cors.allow-credentials` are bound but ignored by both CORS layers — `CorsFilter` and `addCorsMappings` each hardcode `"*"` and `true`, and `getAllowedHeadersList()` is never called | `WebConfig.java` lines 34–35 and 49–50 |
| 9 | `.gitignore` has no `.env` entry, so a locally created `.env` is tracked by default | `.gitignore` |
| 10 | Two Jackson majors resolve — Jackson 3 (`tools.jackson.core:jackson-databind:3.1.0`, via `spring-boot-starter-jackson:4.0.5`) serializes responses and reads `spring.jackson.*`; `JacksonConfig` declares a Jackson 2 `com.fasterxml.jackson.databind.ObjectMapper` built with a bare `new ObjectMapper()`, which serializes nothing on the response path and is used only to parse the multipart `data` part. It honors none of the `spring.jackson.*` keys | `JacksonConfig.java`, `pom.xml`, `application.yaml` lines 54–58 |
| 11 | `S3Config` sets only region and static credentials on `S3Client.builder()` — there is no endpoint-override key, so no S3-compatible non-AWS endpoint can be configured | `S3Config.java` |

---

## Running locally

### Prerequisites

| Requirement | Version / detail |
|---|---|
| JDK | **21** (`<java.version>21</java.version>` in `pom.xml`) |
| Maven | Use the bundled wrapper — `.mvn/wrapper/maven-wrapper.properties` pins Apache Maven **3.9.14** |
| PostgreSQL | Any version supporting `pg_trgm`. The connecting role must be able to `CREATE EXTENSION` and `ALTER TABLE ... ADD CONSTRAINT` |
| S3 | A real AWS S3 bucket plus an access key / secret key pair. `S3Config` sets only `region` and static credentials on `S3Client.builder()` — there is no `endpointOverride` key, so a non-AWS S3-compatible service (MinIO, LocalStack) cannot be pointed at through configuration, and there is no local-filesystem fallback for media storage |

Create the database and enable the extension before first boot:

```bash
createdb khi_archive
psql -d khi_archive -c "CREATE EXTENSION IF NOT EXISTS pg_trgm;"
```

Then write your `.env` at the project root from the [`.env.example`](#envexample) block above —
after adding `.env` to `.gitignore`.

### Build and run

```bash
# Compile and run the test suite
./mvnw clean verify

# Package a runnable jar without running tests
./mvnw -DskipTests clean package

# Run from source (devtools restart + livereload active)
./mvnw spring-boot:run

# Run the packaged jar
java -jar target/khi_archive_platform_backend-0.0.1-SNAPSHOT.jar
```

Any property can be overridden on the command line without touching `.env`:

```bash
./mvnw spring-boot:run -Dspring-boot.run.arguments="--server.port=9090 --app.seed.load=false"
```

### Confirming the app came up

**1. Startup log.** Look for the Tomcat bind line, the schema initializers, and the ready line:

```
Tomcat started on port 8080 (http) ...
users_tbl_role_check re-synced with Role enum: 'GUEST','EMPLOYEE','TEACHER','ADMIN'
Started KhiArchivePlatformApplication in 12.345 seconds ...
```

The first and third lines are emitted by Spring Boot itself and carry a version-dependent suffix
(context path, process uptime), so they are shown truncated. The middle line is this codebase's own:
`UserRoleConstraintInitializer` logs `"users_tbl_role_check re-synced with Role enum: {}"` with the
comma-joined, single-quoted values of `user/enums/Role.java` — today `GUEST`, `EMPLOYEE`, `TEACHER`,
`ADMIN`, in declaration order. If `APP_SEED_LOAD=true` you will also see the seed banner:

```
=== SEED LOAD START — reading from /Users/you/khi_archive_platform_backend/seed-data ===
=== SEED LOAD DONE ===
```

**2. Anonymous HTTP check.** `SecurityConfig` maps `/api/guest/**` to `permitAll()` for every method
(the controllers define GET handlers only), so it needs no token:

```bash
curl -s -o /dev/null -w '%{http_code}\n' "{{BASE_URL}}/api/guest/categories"
# expect 200
```

**3. Authenticated check.** Log in, then call any staff endpoint with the cookie:

```bash
curl -s "{{BASE_URL}}/api/items?page=0&size=1" \
  -H "Cookie: khi_auth_token=$TOKEN"
```

**A note on `/actuator/health`.** `spring-boot-starter-actuator` is a dependency, but
`SecurityConfig` ends its chain with `.anyRequest().authenticated()` and no actuator path is
permitted — an anonymous `curl {{BASE_URL}}/actuator/health` returns **401**, not `{"status":"UP"}`.
A `401` still proves the port is bound and the filter chain is running, so it is a valid liveness
signal, but do not configure an external health probe to expect `200` on that path without first
adding an explicit `permitAll()` matcher. No `management.*` keys are set in `application.yaml`, so
actuator exposure is Spring Boot's default.

### Common startup failures

| Symptom | Cause |
|---|---|
| `Could not resolve placeholder 'PGHOST'` (or `JWT_SECRET`, `AWS_ACCESS_KEY_ID`, …) | A mandatory variable is unset and `.env` was not found at the project root |
| `IllegalStateException: jwt.expiration-ms must be greater than zero` at login | `JWT_EXPIRATION_MS` set to `0` or a negative value |
| `Failed to ensure pg_trgm extension` warning, then no search indexes | `PGUSER` lacks rights to create the extension. Install it as superuser once |
| `violates check constraint "..._check"` when saving a new enum value | The constraint initializer for that table did not run or failed — check the `ApplicationReadyEvent` warnings in the log |
| Browser sends no cookie after a successful login | `JWT_COOKIE_SECURE=true` over plain HTTP, or `SameSite=None` without `Secure` |
| Browser reports a CORS error on a real 401/403 | Origin absent from both `ALWAYS_ALLOWED_ORIGINS` and `CORS_ALLOWED_ORIGINS` |

---

## Notes

- **Table names.** Every table named in this document is taken from an explicit
  `@Table(name = ...)` annotation — `sessions` (`user/model/Session.java`), `users_tbl`
  (`user/model/User.java`), `token_blacklist` (`user/model/TokenBlacklist.java`),
  `physical_media_types` (`platform/model/physicalmedia/PhysicalMediaType.java`) — or from a literal
  SQL string. No name on this page was inferred from Hibernate's implicit CamelCase-to-snake_case
  naming strategy. The names `audios`, `videos`, `images`, `texts`, `projects`, `person` and
  `categories` are quoted verbatim from the SQL string list in `MediaSearchIndexInitializer.java`,
  not derived from class names.
- **Column names.** The only columns named here — `users_tbl.role` and the `version` column
  backfilled by `MediaSearchIndexInitializer` — appear literally in the quoted SQL.
- **Constraint names.** `users_tbl_role_check` is the literal string in the `ADD CONSTRAINT`
  statement; the five `*_audit_logs_action_check` names are the literal entries of the
  `List.of(...)` that `MediaSearchIndexInitializer.dropStaleAuditCheckConstraints()` iterates.
- **Connection pool, log shipping, actuator exposure and profile-specific overrides** are
  `_Not documented in source._`

---

## Related

- [Operations index](./README.md)
- [Internal docs index](../README.md)
- [Conventions — page envelope, timestamps, error shape](../01-conventions.md)
- [Database schema and ERD](../database/README.md) — the tables `ddl-auto=update` maintains
- [Migrations and startup initializers](../database/migrations.md) — the full `ApplicationReadyEvent` SQL set
- [Caching](./caching.md) — the Caffeine cache names, TTLs and eviction points from `CacheConfig`
- [Storage and media](./storage-and-media.md) — how `aws.s3.*` maps onto object keys and the proxy
- [Seeding](./seeding.md) — `app.seed.*` and the `seed-data/*.json` format
