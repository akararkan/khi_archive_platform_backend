# Website Search — One Keyword, Every Media Type

> **Audience:** the public KHI website (frontend + its own backend), third-party clients ·
> **Base path:** `/api/guest/media` ·
> **Source:** `src/main/java/ak/dev/khi_archive_platform/platform/api/guest/GuestMediaSearchAPI.java`,
> `src/main/java/ak/dev/khi_archive_platform/platform/service/guest/GuestMediaSearchService.java`,
> `src/main/java/ak/dev/khi_archive_platform/platform/service/guest/GuestMediaRelevanceScorer.java`,
> `src/main/java/ak/dev/khi_archive_platform/platform/dto/guest/GuestMediaSearchParams.java`

This page documents the two endpoints the website's search box talks to when the visitor picks
**the platform** as the source they want searched. A visitor types `Hasan Zirak`, chooses the
platform, and expects one thing back: *the media this archive holds about Hasan Zirak — the
sounds, the videos, the photographs and the files*. These two endpoints answer exactly that
question, in one call each.

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/api/guest/media/search` | One keyword, all four media kinds, ranked together, with per-kind counts |
| `GET` | `/api/guest/media/{type}/{code}` | Open any one result, whatever kind it turned out to be |

Neither needs a token. Both are covered by the same `permitAll()` rule as the rest of
`/api/guest/**`, and the JWT filter skips the prefix entirely, so a stale `khi_auth_token`
cookie cannot break them.

---

## Contents

- [Why these endpoints exist](#why-these-endpoints-exist)
- [`GET /api/guest/media/search`](#get-apiguestmediasearch)
  - [Query parameters](#query-parameters)
  - [Response shape](#response-shape)
  - [The result card — `GuestMediaHitDTO`](#the-result-card--guestmediahitdto)
  - [How ranking works](#how-ranking-works)
  - [Counts, paging and the `truncated` flag](#counts-paging-and-the-truncated-flag)
  - [Facets](#facets)
- [`GET /api/guest/media/{type}/{code}`](#get-apiguestmediatypecode)
- [Website integration guide](#website-integration-guide)
- [Worked examples](#worked-examples)
- [Errors and edge cases](#errors-and-edge-cases)
- [Choosing between this and the older endpoints](#choosing-between-this-and-the-older-endpoints)

---

## Why these endpoints exist

The platform already had three ways to search, and none of them is the shape a website search
page wants:

| Existing endpoint | What it gives you | Why the search page still struggles |
|---|---|---|
| `GET /api/guest/search` | Seven sections — projects, categories, persons and the four media kinds — top-N each | Capped per section, and each section's `total` is only the number of items it returned, so you cannot draw "312 results" or page past the first N |
| `GET /api/guest/feed` | The four media kinds, grouped, correctly paged | Four parallel lists that are never ranked against each other, so there is no "most relevant result" across kinds |
| `GET /api/guest/audios`, `/videos`, `/texts`, `/images` | Deep per-kind filters and real paging | Four separate calls, four different DTO shapes, four different card components, and the counts for the other three tabs are unknown until you call them |

`/api/guest/media/search` collapses all of that into one request:

- **One list.** Every kind is flattened onto the same card shape, so one component renders the
  whole result list. `type` + `code` on each card is all you need to open it.
- **One ranking scale.** Kind-specific SQL rankings are four incomparable orderings. Every hit is
  re-scored in Java on a single scale, so a strongly matching photograph can legitimately outrank
  a weakly matching recording.
- **All four counts, always.** The tab bar is drawn from one call, whichever tab is selected.
- **Refine counts over the matched set.** `facets=true` counts languages, regions, tags, people
  and decades among *these results*, not across the whole archive.

It adds reach, not access. Every row still passes the same public-visibility gate the rest of the
guest API applies — see [`05-catalog.md`](./05-catalog.md#visibility-gate) — and the response
carries only `Guest…DTO` shapes, so S3 paths, bit rate, file size, version internals, audit and
trash bookkeeping never leave the API.

---

## `GET /api/guest/media/search`

```
GET {{BASE_URL}}/api/guest/media/search?q=Hasan%20Zirak&page=0&size=24&facets=true
```

Every parameter is optional. A bare `GET /api/guest/media/search` is a valid call: it returns the
newest public media of every kind, which is exactly what an empty search box should show.

### Query parameters

**Keyword**

| Param | Type | Default | Notes |
|---|---|---|---|
| `q` | string | — | What the visitor typed. Matched across titles, codes, credits, the owning project and person, categories, tags, keywords, subjects, genres, places and free text, on all four kinds. Blank or absent means *browse*, not *no results*. |

**Selection and shaping**

| Param | Type | Default | Notes |
|---|---|---|---|
| `type` | repeatable / comma list | all four | `audio`, `video`, `image`, `text`. Aliases: `sound(s)`→audio, `photo(s)`→image, `file(s)`, `document(s)`→text. `type=audio&type=video` and `type=audio,video` are equivalent. Selects what `content` contains — **never** what `counts` reports. An unrecognized value is ignored rather than emptying the page. |
| `sort` | string | `relevance` with `q`, `newest` without | `relevance`, `newest`, `oldest`, `title`, `trending` |
| `include` | string | `summary` | `full` additionally attaches the complete kind-specific DTO on `audio`/`video`/`image`/`text` |
| `groupBy` | string | `none` | `type` additionally returns the same results split per kind under `groups` |
| `facets` | boolean | `false` | `true` adds refine-panel counts over the matched set |
| `page` | int | `0` | Zero-based, on the merged list |
| `size` | int | `24` | Clamped to `100` |

**Filters** — all optional, all compose with `q` and with each other.

| Param | Type | Matches |
|---|---|---|
| `projectCode` | string | Exact project code |
| `categoryCode` | string | Exact category code, via the owning project |
| `personCode` | string | Exact person code, via the owning project |
| `language` | string | Exact, case-insensitive |
| `dialect` | string | Exact, case-insensitive |
| `region` | string | Exact, case-insensitive |
| `subject` | repeatable | Any-match against the item's subjects |
| `genre` | repeatable | Any-match against the item's genres |
| `tag` | repeatable | Any-match against the item's tags |
| `keyword` | repeatable | Any-match against the item's keywords |
| `dateFrom` | ISO date or instant | Inclusive lower bound on `dateCreated` |
| `dateTo` | ISO date or instant | Inclusive upper bound; a plain `2019-12-31` covers the whole day |
| `decade` | `1970` or `1970s` | Keeps only that decade — the value the `decades` facet reports |

Date parsing is deliberately lenient, matching the rest of the guest API: an ISO instant
(`2020-01-01T00:00:00Z`), an ISO local date-time (read as UTC) and a plain ISO date all work, and
an unparseable value is ignored rather than failing the request. The same rule applies to
`decade`: a bad value widens the search, it never empties it.

### Response shape

```jsonc
{
  "query": "Hasan Zirak",
  "type": "all",
  "sort": "relevance",
  "order": ["audio", "video", "image", "text"],

  "counts": { "total": 41, "audio": 26, "video": 4, "image": 9, "text": 2 },

  "content": [ /* GuestMediaHitDTO, ranked */ ],

  "page": 0,
  "size": 24,
  "totalElements": 41,
  "totalPages": 2,
  "numberOfElements": 24,
  "first": true,
  "last": false,
  "empty": false,
  "hasNext": true,
  "hasPrevious": false,

  "groups": null,      // only when groupBy=type
  "facets": null,      // only when facets=true
  "truncated": false
}
```

`counts` covers all four kinds regardless of `type`; `totalElements` covers only the selected
kinds. With `type=all` the two agree.

Note that `null` properties are omitted from the actual JSON — see
[`01-conventions.md`](./01-conventions.md#omitted-null-fields). They are written out above only to
show where they sit.

### The result card — `GuestMediaHitDTO`

Every entry in `content` has the same fields whatever its kind. Kind-specific extras are present
where they apply and absent where they do not.

| Field | Type | Notes |
|---|---|---|
| `type` | string | `audio` \| `video` \| `image` \| `text` |
| `code` | string | `audioCode`, `videoCode`, `imageCode` or `textCode` |
| `id` | number | Internal id; prefer `code` for routing |
| `title` | string | Best available: original → Central Kurdish → romanized → alternative → code |
| `subtitle` | string | The next-best title, when it differs |
| `titleInCentralKurdish`, `romanizedTitle` | string | For bilingual display |
| `description` | string | Trimmed to 320 characters on a word boundary, suffixed `…` when cut |
| `creator` | string | Singer (or speaker) for audio, director for video, photographer for image, author for text |
| `creatorRole` | string | Which field `creator` came from — label it, don't guess |
| `projectCode`, `projectName` | string | The owning collection |
| `person` | object | `{ id, personCode, fullName, nickname, romanizedName, mediaPortrait }` |
| `categories` | array | `[{ id, categoryCode, name }]` |
| `language`, `dialect`, `region` | string | |
| `subject`, `genre`, `tags`, `keywords` | string[] | |
| `duration` | string | Audio and video only |
| `pageCount`, `documentType` | number, string | Text only |
| `dateCreated`, `datePublished` | ISO instant | |
| `mediaUrl` | string | Host-relative byte-proxy path — stream, view or read |
| `thumbnailUrl` | string | The image itself, the text cover, or the person's portrait for sounds and videos. May be absent |
| `detailUrl` | string | `/api/guest/media/{type}/{code}` — ready to call |
| `score` | number | Relevance for this query; `0` when no `q` was sent |
| `matchedIn` | string[] | Field groups that matched, strongest first |
| `trending`, `trendingRank`, `trendingScore` | boolean, number, number | From the cached trending snapshot |
| `audio` / `video` / `image` / `text` | object | Only with `include=full`; exactly one is present, matching `type` |

`matchedIn` uses a small closed vocabulary, so you can map it to UI labels once:

`title` · `code` · `creator` · `person` · `project` · `category` · `tags` · `keywords` ·
`subject` · `genre` · `place` · `description`

### How ranking works

With `sort=relevance` (the default whenever `q` is present) the order comes from a score built
per hit:

1. **Tokenize.** The query splits on whitespace; tokens without a letter or digit are dropped.
   `Hasan Zirak` → `["hasan", "zirak"]`.
2. **Score each token against each field group** as *group weight × match strength*.

   | Group | Weight | | Match strength | Value |
   |---|---|---|---|---|
   | `title` | 10 | | field equals the token | 3.0 |
   | `code` | 9 | | field starts with the token | 2.0 |
   | `creator`, `person` | 8 | | a word inside the field starts with it | 1.6 |
   | `project` | 5 | | any other substring | 1.0 |
   | `tags`, `keywords`, `category` | 4 | | no match | 0 |
   | `subject`, `genre` | 3 | | | |
   | `place` | 2 | | | |
   | `description` | 1 | | | |

3. **Keep each token's best group**, then average across tokens — so a two-word query is not
   penalized against a one-word one.
4. **Add the bonuses.** All tokens matched somewhere: `+6`. The whole multi-word phrase appears
   verbatim in a title: `+8`; elsewhere: `+2`. A currently-trending item in the top 20 gets up to
   `+2`.
5. **Break ties by the database's own ordering.** Each kind's rows arrive already ranked by the
   two-phase fuzzy SQL search (prefix, substring, then trigram similarity — see
   [`04-discovery.md`](./04-discovery.md)); that position folds in as a decreasing nudge of up to
   `1.5`, so exact ties keep the database's judgement instead of being reordered arbitrarily.

The practical effect: a recording *titled* "Hasan Zirak — Live in Sulaymaniyah" beats a
photograph whose description merely mentions him, and a recording whose **person** is Hasan Zirak
beats one that names him once in a note — across kinds, in one list.

The other sorts ignore the score entirely: `newest` and `oldest` order by `dateCreated` (falling
back to `datePublished`), `title` orders case-insensitively, and `trending` orders by
`trendingScore` with the relevance score as the tiebreak. All of them fall back to `code` last,
so paging is stable.

### Counts, paging and the `truncated` flag

Each kind contributes at most **500 rows** — the same cap the guest keyword search already
applies before any of this runs. Because that cap sits upstream, a keyword search never loses
anything here that it would not have lost by calling `/api/guest/audios` directly.

`counts` and `totalElements` come from the same scan, so a tab's count is always exactly the
`totalElements` you get back when you select that tab. When any kind reaches the cap, `truncated` is `true`: the counts are then a
floor rather than an exact total, and the caller should narrow the query or add a filter. In
practice `truncated` only appears on filter-only browsing with no `q`; that is what
[`/api/guest/feed`](./04-discovery.md) is for.

Paging applies to the merged list, and independently to each `groups` section. A page past the
end returns an empty `content` with the correct `totalElements`, never an error.

### Facets

`facets=true` adds counts computed over the matched set:

```jsonc
"facets": {
  "languages": [{ "label": "Kurdish", "count": 31 }],
  "dialects":  [{ "label": "Sorani",  "count": 22 }],
  "regions":   [{ "label": "Sulaymaniyah", "count": 18 }],
  "subjects":  [{ "label": "Music", "count": 26 }],
  "genres":    [{ "label": "Folk",  "count": 14 }],
  "tags":      [{ "label": "concert", "count": 9 }],
  "keywords":  [{ "label": "live", "count": 7 }],
  "persons":   [{ "code": "PER-001", "label": "Hasan Zirak", "count": 33 }],
  "projects":  [{ "code": "PRJ-014", "label": "Radio Recordings", "count": 12 }],
  "decades":   [{ "label": "1950s", "count": 4 }, { "label": "1960s", "count": 19 }]
}
```

Each list holds at most 30 buckets, ordered by count descending then label ascending — except
`decades`, which reads as a timeline and is ordered oldest first.

Every bucket is directly actionable. `label` is what you send back as `language=`, `region=`,
`tag=`, `keyword=`, `subject=`, `genre=` or `decade=`; for `persons` and `projects`, send the
`code` as `personCode=` or `projectCode=`.

Facets cover the whole matched set for the **selected** kinds — not just the current page — so the
numbers stay put as the visitor pages through results, and narrow when they switch tabs.

---

## `GET /api/guest/media/{type}/{code}`

Opens one item using the `type` + `code` pair every search result already carries, so the
frontend never has to route `audio` to `/audios/{code}` and `text` to `/texts/{code}` itself.

```
GET {{BASE_URL}}/api/guest/media/audio/AUD-001
GET {{BASE_URL}}/api/guest/media/text/TXT-014?related=false
```

| Param | Where | Default | Notes |
|---|---|---|---|
| `type` | path | — | `audio`, `video`, `image`, `text`, plus the aliases `sound`, `photo`, `file`, `document` |
| `code` | path | — | The item's public code |
| `related` | query | `true` | `false` skips loading the rest of the project |

```jsonc
{
  "type": "audio",
  "code": "AUD-001",
  "item":  { /* the same flat card the search returns — for the page header */ },
  "audio": { /* the complete GuestAudioDTO, identical to GET /api/guest/audios/AUD-001 */ },
  "related": [ /* up to 12 cards from the same project, this item excluded */ ]
}
```

Exactly one of `audio`, `video`, `image`, `text` is present — the one naming `type`. The payload
is byte-for-byte what the per-kind endpoint returns, so a page already bound to
`GuestAudioDTO` needs no changes.

`related` is the "more from this collection" rail. It is interleaved kind by kind — one audio,
one video, one image, one text, then round again — so the rail never fills up with thirty
photographs from the same shoot. It is empty when the item has no project, or the project holds
nothing else public.

Like every other guest detail endpoint, this one logs a view for the trending pipeline (see
[`04-discovery.md`](./04-discovery.md#how-discovery-feeds-trending)).

**404** is returned when the code is unknown, when `type` is not one of the four kinds, and when
the item is trashed or not public. The three cases are deliberately indistinguishable, so the
endpoint cannot be used to probe for the existence of non-public records.

---

## Website integration guide

> The steps below are the short version. For the full implementation — the files to create in
> order, the fetch hook with abort and race-guard, URL-as-state, the components, RTL handling,
> the QA matrix and the common mistakes — see
> [`11-search-frontend-guide.md`](./11-search-frontend-guide.md).

### 1. Wire the source selector

The website's search box lets the visitor choose where to search. Picking **the platform** means
one base URL and one endpoint:

```js
const PLATFORM_API = 'https://api.khi.example';   // your {{BASE_URL}}

export async function searchPlatform(query, opts = {}) {
  const params = new URLSearchParams();
  if (query?.trim()) params.set('q', query.trim());
  if (opts.type && opts.type !== 'all') params.set('type', opts.type);
  if (opts.sort) params.set('sort', opts.sort);
  params.set('page', String(opts.page ?? 0));
  params.set('size', String(opts.size ?? 24));
  if (opts.facets) params.set('facets', 'true');

  for (const [key, values] of Object.entries(opts.filters ?? {})) {
    for (const v of [].concat(values)) params.append(key, v);
  }

  const res = await fetch(`${PLATFORM_API}/api/guest/media/search?${params}`);
  if (!res.ok) throw new Error(`Platform search failed: ${res.status}`);
  return res.json();
}
```

No token, no headers, no cookie. If the website calls from the browser rather than from its own
backend, check the CORS allowlist in [`01-conventions.md`](./01-conventions.md#cors).

### 2. Draw the tab bar from `counts`

`counts` always covers all four kinds, so one call fills every tab — including the ones the
visitor has not clicked:

```jsx
const TABS = [
  { key: 'all',   label: 'All',    n: r.counts.total },
  { key: 'audio', label: 'Sounds', n: r.counts.audio },
  { key: 'video', label: 'Videos', n: r.counts.video },
  { key: 'image', label: 'Photos', n: r.counts.image },
  { key: 'text',  label: 'Files',  n: r.counts.text  },
];
```

Switching tabs re-issues the same query with `type=<key>` and `page=0`. Because `counts` does not
depend on `type`, the numbers stay put while the list changes.

### 3. Render one card for every kind

```jsx
function ResultCard({ hit }) {
  const src = hit.thumbnailUrl ? PLATFORM_API + hit.thumbnailUrl : null;
  return (
    <a href={`/item/${hit.type}/${hit.code}`}>
      {src ? <img src={src} alt="" loading="lazy" /> : <KindIcon type={hit.type} />}
      <h3>{hit.title}</h3>
      {hit.subtitle && <p className="alt">{hit.subtitle}</p>}
      {hit.creator && <p>{labelFor(hit.creatorRole)}: {hit.creator}</p>}
      {hit.duration && <span>{hit.duration}</span>}
      {hit.pageCount && <span>{hit.pageCount} pages</span>}
      <p>{hit.description}</p>
      {hit.matchedIn.length > 0 && <small>Matched in {hit.matchedIn.join(', ')}</small>}
    </a>
  );
}
```

`mediaUrl` and `thumbnailUrl` are **host-relative** — always prepend your API base URL. Never
build an S3 URL by hand; the bucket is not reachable from a browser and the object key is not in
the response. See [`07-streaming.md`](./07-streaming.md#where-the-urls-come-from).

### 4. Open a result

The card's `type` + `code` is the whole routing contract:

```js
const item = await fetch(`${PLATFORM_API}/api/guest/media/${type}/${code}`).then(r => r.json());
const payload = item[item.type];       // item.audio | item.video | item.image | item.text
```

Then play it with `PLATFORM_API + payload.audioFileUrl` (or `videoFileUrl`, `imageFileUrl`,
`textFileUrl`). Range requests and `ETag` revalidation are documented in
[`07-streaming.md`](./07-streaming.md).

### 5. Build the refine panel from `facets`

Ask for facets on the first page of a query and reuse them while the visitor pages:

```js
const filters = {};                             // language: ['Kurdish'], tag: ['concert'], …
function toggle(param, value) {
  const set = new Set(filters[param] ?? []);
  set.has(value) ? set.delete(value) : set.add(value);
  filters[param] = [...set];
  return searchPlatform(query, { filters, facets: true, page: 0 });
}
```

Bucket `label` goes back as `language`, `region`, `tag`, `keyword`, `subject`, `genre` or
`decade`; bucket `code` goes back as `personCode` or `projectCode`.

### 6. Autocomplete while typing

Keep using [`GET /api/guest/suggest`](./04-discovery.md) for the dropdown under the search box —
it is deliberately lighter than a full search. Run the full search only on submit, or on a
debounce of 300 ms or more.

### 7. Practical notes

- **Empty query is valid.** `GET /api/guest/media/search` with no `q` returns the newest public
  media — a good default state for an empty search page.
- **Ask for `include=full` only on pages that need it.** The default card is enough for a result
  grid; `include=full` roughly triples the payload.
- **`groupBy=type`** gives you the "3 sounds · 2 videos · 4 photos" preview layout in the same
  call that fills the merged list. Use it for the *All* tab if you prefer sections to one list.
- **Cache on your side, not ours.** Responses are not cached server-side; a repeat query re-runs
  the search. A short client-side or edge cache keyed on the full query string is worth having.
- **Every call with a non-blank `q` is logged** to `guest_search_logs` and feeds
  `GET /api/guest/trending`. This is how "Popular Searches" gets its content.

---

## Worked examples

**The headline case — "Hasan Zirak", everything, with tabs and refine counts**

```bash
curl -s "{{BASE_URL}}/api/guest/media/search?q=Hasan%20Zirak&size=24&facets=true"
```

**Only his recordings, newest first**

```bash
curl -s "{{BASE_URL}}/api/guest/media/search?q=Hasan%20Zirak&type=audio&sort=newest"
```

**Sounds and videos together, from the 1970s**

```bash
curl -s "{{BASE_URL}}/api/guest/media/search?q=Hasan%20Zirak&type=sounds,videos&decade=1970s"
```

**Everything the archive holds for one person, no keyword at all**

```bash
curl -s "{{BASE_URL}}/api/guest/media/search?personCode=PER-001&sort=newest&size=48"
```

**One call, grouped preview per kind, with full payloads**

```bash
curl -s "{{BASE_URL}}/api/guest/media/search?q=Hasan%20Zirak&groupBy=type&include=full&size=6"
```

**Narrow by language and tag, then page**

```bash
curl -s "{{BASE_URL}}/api/guest/media/search?q=maqam&language=Kurdish&tag=concert&tag=live&page=1&size=24"
```

**Open a result and skip the related rail**

```bash
curl -s "{{BASE_URL}}/api/guest/media/audio/AUD-001?related=false"
```

**A trimmed response**

```jsonc
{
  "query": "Hasan Zirak",
  "type": "all",
  "sort": "relevance",
  "order": ["audio", "video", "image", "text"],
  "counts": { "total": 41, "audio": 26, "video": 4, "image": 9, "text": 2 },
  "content": [
    {
      "type": "audio",
      "code": "AUD-0142",
      "title": "Hasan Zirak — Live in Sulaymaniyah",
      "subtitle": "حەسەن زیرەک",
      "creator": "Hasan Zirak",
      "creatorRole": "singer",
      "projectCode": "PRJ-014",
      "projectName": "Radio Recordings",
      "person": { "personCode": "PER-001", "fullName": "Hasan Zirak" },
      "language": "Kurdish",
      "region": "Sulaymaniyah",
      "tags": ["concert", "live"],
      "duration": "04:12",
      "dateCreated": "1971-06-01T00:00:00Z",
      "mediaUrl": "/api/guest/audio/AUD-0142/stream",
      "thumbnailUrl": "/api/guest/image/IMG-0007/view",
      "detailUrl": "/api/guest/media/audio/AUD-0142",
      "score": 34.117,
      "matchedIn": ["title", "person", "creator"]
    }
  ],
  "page": 0,
  "size": 24,
  "totalElements": 41,
  "totalPages": 2,
  "hasNext": true,
  "truncated": false
}
```

---

## Errors and edge cases

| Situation | Behaviour |
|---|---|
| No `q` and no filters | `200` with the newest public media of every kind |
| Nothing matches | `200` with `content: []`, `counts` all zero, `empty: true` — never a 404 |
| Unknown `type` value | Ignored; the search widens to all four kinds rather than emptying |
| Unparseable `dateFrom` / `dateTo` / `decade` | Ignored; the filter is simply not applied |
| `size` above 100 | Clamped to 100 |
| `page` past the end | `200` with an empty `content` and the correct `totalElements` |
| Detail: unknown code, unknown type, or a non-public item | `404` with an empty body, indistinguishable from one another |

Failures use the standard `ApiErrorResponse` envelope documented in
[`02-errors.md`](./02-errors.md). These two endpoints do not introduce any new `ErrorCode`.

---

## Choosing between this and the older endpoints

| Building | Use |
|---|---|
| The website's search results page | `GET /api/guest/media/search` |
| A detail page reached from a search result | `GET /api/guest/media/{type}/{code}` |
| The autocomplete dropdown under the search box | `GET /api/guest/suggest` — [`04-discovery.md`](./04-discovery.md) |
| The home page's browse feed, with no keyword | `GET /api/guest/feed` — [`04-discovery.md`](./04-discovery.md) |
| A kind-specific catalog with deep filters (singer, ISBN, colour, photostory…) | `GET /api/guest/audios`, `/videos`, `/texts`, `/images` — [`06-media.md`](./06-media.md) |
| Project, category and person pages | [`05-catalog.md`](./05-catalog.md) |
| Playing or reading the bytes | [`07-streaming.md`](./07-streaming.md) |

Nothing was removed. `/api/guest/search`, `/feed` and the four per-kind catalogs behave exactly as
they did; this page documents an addition alongside them.

---

## Related

- [`04-discovery.md`](./04-discovery.md) — trending, the older cross-entity `/search`, `/suggest`,
  `/facets` and the grouped `/feed`
- [`06-media.md`](./06-media.md) — the per-kind catalogs and the full `Guest…DTO` field lists
  behind `include=full`
- [`07-streaming.md`](./07-streaming.md) — what `mediaUrl` and `thumbnailUrl` point at, and how
  `Range` and `ETag` behave
- [`05-catalog.md`](./05-catalog.md#visibility-gate) — the visibility rules every hit has already
  passed
- [`01-conventions.md`](./01-conventions.md) — paging, date formats, omitted `null` fields, CORS
- [`11-search-frontend-guide.md`](./11-search-frontend-guide.md) — the frontend implementation
  guide for everything on this page
- [`09-recipes.md`](./09-recipes.md) — end-to-end curl walkthroughs for the other public flows
