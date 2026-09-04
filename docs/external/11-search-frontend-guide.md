# Frontend Guide — Building the Search Page

> **Audience:** whoever writes the search UI — the KHI website and the platform SPA ·
> **Implements:** [`10-website-search.md`](./10-website-search.md) ·
> **Endpoints used:** `GET /api/guest/media/search`, `GET /api/guest/media/{type}/{code}`,
> `GET /api/guest/suggest` ·
> **Frontend conventions taken from:** [`../FRONTEND_INTEGRATION.md`](../FRONTEND_INTEGRATION.md)
> (`src/lib/api-client.js`, `src/lib/media-url.js`, `src/services/*.js`)

[`10-website-search.md`](./10-website-search.md) is the API reference — every parameter, every
field, every status. **This page is the implementation guide**: the files to create, in order, with
the code that goes in them, and the mistakes that cost an afternoon.

The feature being built: a visitor types `Hasan Zirak`, picks **the platform** as the source, and
gets back the sounds, videos, photographs and files this archive holds about them — one ranked
list, one tab bar with real counts, one refine panel, and a detail page that opens any result
whatever kind it turned out to be.

---

## Contents

- [What you are building](#what-you-are-building)
- [File map](#file-map)
- [Step 1 — the service module](#step-1--the-service-module)
- [Step 2 — the URL is the state](#step-2--the-url-is-the-state)
- [Step 3 — the data hook](#step-3--the-data-hook)
- [Step 4 — the components](#step-4--the-components)
- [Step 5 — the detail page](#step-5--the-detail-page)
- [Step 6 — media URLs and players](#step-6--media-urls-and-players)
- [Autocomplete](#autocomplete)
- [Kurdish, Arabic and RTL](#kurdish-arabic-and-rtl)
- [Error handling](#error-handling)
- [Performance checklist](#performance-checklist)
- [Accessibility checklist](#accessibility-checklist)
- [QA matrix](#qa-matrix)
- [Common mistakes](#common-mistakes)

---

## What you are building

```
┌───────────────────────────────────────────────────────────────────────────┐
│  [ Hasan Zirak                    ]  [ Source: Platform ▾ ]   [ Search ]   │  ← SearchBar
├───────────────────────────────────────────────────────────────────────────┤
│  All 41 │ Sounds 26 │ Videos 4 │ Photos 9 │ Files 2      Sort: Relevance ▾ │  ← TabBar   ← counts
├──────────────────┬────────────────────────────────────────────────────────┤
│ REFINE           │  ┌──────────┐ ┌──────────┐ ┌──────────┐                │
│ Language         │  │ ▶ AUDIO  │ │ ▶ VIDEO  │ │ 🖼 IMAGE  │                │  ← ResultGrid
│  ☑ Kurdish  31   │  │ Hasan…   │ │ Hasan…   │ │ Hasan…   │                │     one card,
│ Decade           │  │ singer   │ │ director │ │ photog.  │                │     four kinds
│  ☐ 1950s     4   │  └──────────┘ └──────────┘ └──────────┘                │
│  ☐ 1960s    19   │                                                        │
│ Person           │  ‹ 1  2 ›                                              │  ← Pagination
│  ☐ Hasan Z. 33   │                                                        │
└──────────────────┴────────────────────────────────────────────────────────┘
      ↑ facets
```

Four rules make the whole thing simple, and all four are properties of the API rather than things
you have to build:

1. **One request fills the whole page.** Tabs, list, refine panel and pagination all read from a
   single response.
2. **One card component renders four kinds.** Every result has the same fields; `type` is a badge,
   not a branch.
3. **`counts` never changes when the tab changes.** Switching tabs narrows the list, never the
   numbers — so the tab bar does not flicker or renumber.
4. **`type` + `code` is the only routing contract.** The detail page needs nothing else.

---

## File map

Create these, in this order. Paths follow the platform SPA layout
([`../FRONTEND_INTEGRATION.md`](../FRONTEND_INTEGRATION.md)); adapt the folders to the website
project, the code is the same.

| File | Purpose | Step |
|---|---|---|
| `src/services/media-search.js` | The only place that talks to the two endpoints | [1](#step-1--the-service-module) |
| `src/lib/search-params.js` | Query object ⇄ URL search params | [2](#step-2--the-url-is-the-state) |
| `src/hooks/useMediaSearch.js` | Fetch, abort, loading and error state | [3](#step-3--the-data-hook) |
| `src/components/search/SearchBar.jsx` | Input + source selector + submit | [4](#step-4--the-components) |
| `src/components/search/TabBar.jsx` | Kind tabs, driven by `counts` | 4 |
| `src/components/search/ResultCard.jsx` | One card for all four kinds | 4 |
| `src/components/search/ResultGrid.jsx` | List, skeletons, empty state | 4 |
| `src/components/search/RefinePanel.jsx` | Checkbox facets | 4 |
| `src/components/search/Pagination.jsx` | Page controls | 4 |
| `src/pages/public/SearchPage.jsx` | Wires the above together | 4 |
| `src/pages/public/MediaDetailPage.jsx` | `/item/:type/:code` | [5](#step-5--the-detail-page) |

---

## Step 1 — the service module

Everything that touches the API lives here. Nothing else in the app builds a URL.

```js
// src/services/media-search.js
import apiClient from '@/lib/api-client';

/**
 * The four kinds the API returns, in the order it returns them.
 * `label` is the visitor-facing word; `key` is what the API expects.
 */
export const MEDIA_KINDS = [
  { key: 'audio', label: 'Sounds' },
  { key: 'video', label: 'Videos' },
  { key: 'image', label: 'Photos' },
  { key: 'text',  label: 'Files'  },
];

export const SORTS = ['relevance', 'newest', 'oldest', 'title', 'trending'];

/** Spring binds repeated arrays as ?tag=a&tag=b — never ?tag[0]=a. */
const arrayParams = { indexes: null };

/**
 * One search across audio, video, image and text.
 *
 * @param {object} query   the search state — see src/lib/search-params.js
 * @param {AbortSignal} [signal]  cancels a superseded request
 */
export async function searchMedia(query, signal) {
  const { data } = await apiClient.get('/guest/media/search', {
    signal,
    paramsSerializer: arrayParams,
    params: {
      q: query.q || undefined,
      // 'all' is the server default — omitting it keeps the URL clean.
      type: query.type && query.type !== 'all' ? query.type : undefined,
      sort: query.sort || undefined,
      page: query.page ?? 0,
      size: query.size ?? 24,
      facets: query.facets ? true : undefined,
      groupBy: query.groupBy || undefined,
      include: query.include || undefined,
      ...query.filters,          // language, region, tag[], personCode, decade, …
    },
  });
  return data;
}

/** Opens one result using the type + code its card already carries. */
export async function getMediaItem(type, code, { related = true, signal } = {}) {
  const { data } = await apiClient.get(
    `/guest/media/${encodeURIComponent(type)}/${encodeURIComponent(code)}`,
    { signal, params: { related } },
  );
  return data;
}

/** The kind-specific payload of a detail response, without branching on type. */
export function payloadOf(item) {
  return item?.[item?.type] ?? null;   // item.audio | item.video | item.image | item.text
}
```

Three things in there are not optional:

- **No `/api` prefix.** `apiClient.baseURL` already ends in `/api`. Writing `/api/guest/...` here
  produces `/api/api/guest/...` and a 404.
- **`paramsSerializer: { indexes: null }`.** Without it axios sends `tag[0]=concert`, which Spring
  does not bind, and your tag filter silently does nothing. `src/services/guest.js` already does
  this for the same reason.
- **`signal`.** Search requests race. Step 3 explains why the last response is not always the one
  you want.

No token, no headers. These endpoints are anonymous — see
[`10-website-search.md`](./10-website-search.md).

---

## Step 2 — the URL is the state

Put the search state in the URL, not in component state. A search result page that cannot be
shared, bookmarked, or returned to with the back button is a broken search page, and every one of
those behaviours is free if the URL is the source of truth.

```js
// src/lib/search-params.js

/** Filters that may repeat: ?tag=concert&tag=live */
const MULTI = ['subject', 'genre', 'tag', 'keyword'];

/** Filters that appear at most once. */
const SINGLE = [
  'projectCode', 'categoryCode', 'personCode',
  'language', 'dialect', 'region',
  'dateFrom', 'dateTo', 'decade',
];

export const EMPTY_QUERY = {
  q: '', type: 'all', sort: '', page: 0, size: 24, facets: true, filters: {},
};

/** URLSearchParams → the query object the service and the hook both take. */
export function parseQuery(searchParams) {
  const filters = {};
  for (const key of SINGLE) {
    const v = searchParams.get(key);
    if (v) filters[key] = v;
  }
  for (const key of MULTI) {
    const v = searchParams.getAll(key);
    if (v.length) filters[key] = v;
  }
  return {
    ...EMPTY_QUERY,
    q: searchParams.get('q') ?? '',
    type: searchParams.get('type') ?? 'all',
    sort: searchParams.get('sort') ?? '',
    page: Number(searchParams.get('page') ?? 0) || 0,
    size: Math.min(Number(searchParams.get('size') ?? 24) || 24, 100),
    filters,
  };
}

/** The query object → URLSearchParams, dropping everything at its default. */
export function toSearchParams(query) {
  const sp = new URLSearchParams();
  if (query.q) sp.set('q', query.q);
  if (query.type && query.type !== 'all') sp.set('type', query.type);
  if (query.sort) sp.set('sort', query.sort);
  if (query.page) sp.set('page', String(query.page));
  if (query.size && query.size !== 24) sp.set('size', String(query.size));
  for (const [key, value] of Object.entries(query.filters ?? {})) {
    for (const v of [].concat(value)) sp.append(key, v);
  }
  return sp;
}

/** Any change other than paging resets to page 0 — otherwise page 7 of 2 shows nothing. */
export function withChange(query, patch) {
  const next = { ...query, ...patch };
  if (!('page' in patch)) next.page = 0;
  return next;
}

/** Toggles one facet value on or off and resets paging. */
export function toggleFilter(query, param, value) {
  const current = [].concat(query.filters?.[param] ?? []);
  const next = current.includes(value)
    ? current.filter((v) => v !== value)
    : [...current, value];
  const filters = { ...query.filters };
  if (next.length) filters[param] = next;
  else delete filters[param];
  return withChange(query, { filters });
}
```

`withChange` exists because of one bug that appears in every search page ever written: the visitor
is on page 4, types a new keyword, and gets an empty result list. **Every change except paging
resets `page` to 0.**

---

## Step 3 — the data hook

```js
// src/hooks/useMediaSearch.js
import { useEffect, useRef, useState } from 'react';
import { searchMedia } from '@/services/media-search';

export function useMediaSearch(query) {
  const [data, setData] = useState(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(null);
  const requestId = useRef(0);

  // Only re-run when something the API cares about changed.
  const key = JSON.stringify(query);

  useEffect(() => {
    const controller = new AbortController();
    const id = ++requestId.current;

    setLoading(true);
    setError(null);

    searchMedia(query, controller.signal)
      .then((res) => {
        if (id !== requestId.current) return;   // a newer search already answered
        setData(res);
      })
      .catch((err) => {
        if (controller.signal.aborted || id !== requestId.current) return;
        setError(err);
      })
      .finally(() => {
        if (id === requestId.current) setLoading(false);
      });

    return () => controller.abort();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key]);

  return { data, loading, error };
}
```

**Why both the abort and the `requestId`.** Aborting handles the common case. The counter handles
the one it misses: a fast second response arriving before a slow first one, where the first request
was never aborted because it had already resolved on the network. Keep the results of the newest
request only, and stale results can never overwrite fresh ones.

**Keep the previous results visible while loading.** `setData` is not cleared on a new request, so
the grid keeps the old page and dims it instead of flashing empty. That reads as fast; a blank
screen reads as broken.

---

## Step 4 — the components

### SearchBar — the source selector

The platform is one of several sources the visitor can search. Only the platform branch uses this
guide; keep the choice in the URL too, so a shared link reproduces it.

```jsx
// src/components/search/SearchBar.jsx
export default function SearchBar({ q, source, onSubmit }) {
  const [draft, setDraft] = useState(q);
  useEffect(() => setDraft(q), [q]);   // back button must update the input

  return (
    <form
      role="search"
      onSubmit={(e) => { e.preventDefault(); onSubmit({ q: draft.trim(), source }); }}
    >
      <label htmlFor="site-search" className="sr-only">Search the archive</label>
      <input
        id="site-search"
        type="search"
        value={draft}
        onChange={(e) => setDraft(e.target.value)}
        placeholder="Search — e.g. Hasan Zirak"
        autoComplete="off"
      />
      <SourceSelect value={source} />
      <button type="submit">Search</button>
    </form>
  );
}
```

Submit on **Enter**, not on every keystroke. The full search is a heavier call than the
autocomplete; the dropdown is what should react as the visitor types (see
[Autocomplete](#autocomplete)).

### TabBar — driven entirely by `counts`

```jsx
// src/components/search/TabBar.jsx
import { MEDIA_KINDS } from '@/services/media-search';

export default function TabBar({ counts, active, onChange }) {
  const tabs = [
    { key: 'all', label: 'All', n: counts?.total ?? 0 },
    ...MEDIA_KINDS.map((k) => ({ ...k, n: counts?.[k.key] ?? 0 })),
  ];

  return (
    <div role="tablist" aria-label="Result types">
      {tabs.map((t) => (
        <button
          key={t.key}
          role="tab"
          aria-selected={active === t.key}
          disabled={t.n === 0 && t.key !== 'all'}
          onClick={() => onChange(t.key)}
        >
          {t.label} <span aria-hidden="true">{t.n.toLocaleString()}</span>
          <span className="sr-only">{t.n} results</span>
        </button>
      ))}
    </div>
  );
}
```

`counts` covers all four kinds no matter which tab is selected, so these numbers are correct on
first paint and do not move when the visitor switches tabs. Disabling a zero tab is a small mercy.

### ResultCard — one component, four kinds

```jsx
// src/components/search/ResultCard.jsx
import { Link } from 'react-router-dom';
import { resolveMediaUrl } from '@/lib/media-url';

const KIND_LABEL = { audio: 'Sound', video: 'Video', image: 'Photo', text: 'File' };

const ROLE_LABEL = {
  singer: 'Singer', speaker: 'Speaker', poet: 'Poet', composer: 'Composer',
  producer: 'Producer', author: 'Author', contributors: 'Contributors',
  creatorArtistDirector: 'Director', creatorArtistPhotographer: 'Photographer',
  personShownInVideo: 'Shown', personShownInImage: 'Shown',
  publisher: 'Publisher', printingHouse: 'Printer', contributor: 'Contributor',
};

const MATCH_LABEL = {
  title: 'title', code: 'code', creator: 'credits', person: 'person',
  project: 'collection', category: 'category', tags: 'tags', keywords: 'keywords',
  subject: 'subject', genre: 'genre', place: 'place', description: 'description',
};

export default function ResultCard({ hit }) {
  const thumb = hit.thumbnailUrl ? resolveMediaUrl(hit.thumbnailUrl) : null;
  const matched = (hit.matchedIn ?? []).map((m) => MATCH_LABEL[m] ?? m);

  return (
    <article>
      <Link to={`/item/${hit.type}/${encodeURIComponent(hit.code)}`}>
        <div className="thumb">
          {thumb
            ? <img src={thumb} alt="" loading="lazy" decoding="async" />
            : <KindIcon type={hit.type} />}
          <span className="badge">{KIND_LABEL[hit.type]}</span>
          {hit.trending && <span className="badge">Trending</span>}
        </div>

        <h3>{hit.title}</h3>
        {hit.subtitle && <p lang="ckb" dir="auto">{hit.subtitle}</p>}

        {hit.creator && (
          <p>{ROLE_LABEL[hit.creatorRole] ?? 'Credit'}: {hit.creator}</p>
        )}

        {/* Kind-specific extras — present only where they apply. */}
        {hit.duration && <span>{hit.duration}</span>}
        {hit.pageCount != null && <span>{hit.pageCount} pages</span>}
        {hit.documentType && <span>{hit.documentType}</span>}

        {hit.description && <p className="desc">{hit.description}</p>}

        <footer>
          {hit.projectName && <span>{hit.projectName}</span>}
          {hit.dateCreated && <time dateTime={hit.dateCreated}>{year(hit.dateCreated)}</time>}
          {matched.length > 0 && <small>Matched in {matched.join(', ')}</small>}
        </footer>
      </Link>
    </article>
  );
}

const year = (iso) => new Date(iso).getUTCFullYear();
```

Notes that matter:

- **`description` is already trimmed** to 320 characters on a word boundary by the server. Do not
  truncate it again in CSS and do not fetch `include=full` just to get a longer one.
- **`matchedIn` is a closed vocabulary** of twelve values — map it once, as above, rather than
  printing raw field names at the visitor.
- **`hit.trending`** is the JSON name of the flag (not `isTrending`).
- **Do not branch the component on `type`.** Kind-specific fields are simply absent where they do
  not apply, which is what the `&&` guards handle.

### ResultGrid — loading, empty and truncated states

```jsx
export default function ResultGrid({ data, loading, error, onRetry }) {
  if (error) return <ErrorState error={error} onRetry={onRetry} />;
  if (loading && !data) return <SkeletonGrid count={12} />;
  if (!data) return null;

  if (data.empty) {
    return (
      <EmptyState
        title={data.query ? `No results for “${data.query}”` : 'Nothing here yet'}
        hints={['Check the spelling', 'Try fewer words', 'Clear some filters']}
      />
    );
  }

  return (
    <>
      {data.truncated && (
        <p role="status">
          Showing the strongest matches. Narrow the search for exact totals.
        </p>
      )}
      <div className={loading ? 'grid is-stale' : 'grid'} aria-busy={loading}>
        {data.content.map((hit) => (
          <ResultCard key={`${hit.type}:${hit.code}`} hit={hit} />
        ))}
      </div>
    </>
  );
}
```

`key={`${hit.type}:${hit.code}`}` — **not** `hit.id`. Ids are per-table, so an audio and an image
can both be id 42 in the same merged list, and React will reuse the wrong DOM node.

### RefinePanel — facets straight into filters

```jsx
// src/components/search/RefinePanel.jsx
const FACET_GROUPS = [
  { key: 'languages', param: 'language',    title: 'Language' },
  { key: 'regions',   param: 'region',      title: 'Region'   },
  { key: 'decades',   param: 'decade',      title: 'Decade'   },
  { key: 'genres',    param: 'genre',       title: 'Genre'    },
  { key: 'subjects',  param: 'subject',     title: 'Subject'  },
  { key: 'tags',      param: 'tag',         title: 'Tag'      },
  { key: 'persons',   param: 'personCode',  title: 'Person',  useCode: true },
  { key: 'projects',  param: 'projectCode', title: 'Collection', useCode: true },
];

export default function RefinePanel({ facets, filters, onToggle }) {
  if (!facets) return null;

  return (
    <aside aria-label="Refine results">
      {FACET_GROUPS.map(({ key, param, title, useCode }) => {
        const buckets = facets[key] ?? [];
        if (!buckets.length) return null;
        const active = [].concat(filters?.[param] ?? []);

        return (
          <fieldset key={key}>
            <legend>{title}</legend>
            {buckets.map((b) => {
              const value = useCode ? b.code : b.label;
              return (
                <label key={value}>
                  <input
                    type="checkbox"
                    checked={active.includes(value)}
                    onChange={() => onToggle(param, value)}
                  />
                  {b.label} <span>({b.count})</span>
                </label>
              );
            })}
          </fieldset>
        );
      })}
    </aside>
  );
}
```

The rule the table above encodes: **`persons` and `projects` send `code`; every other facet sends
`label`.** Sending a person's display name as `personCode` matches nothing and returns an empty
page with no error, which is a genuinely hard bug to see.

Ask for `facets=true` on every search. The cost is one pass over results the server already has in
hand, and the panel then stays in step with the list.

### Pagination

```jsx
export default function Pagination({ data, onPage }) {
  if (!data || data.totalPages <= 1) return null;
  return (
    <nav aria-label="Result pages">
      <button disabled={!data.hasPrevious} onClick={() => onPage(data.page - 1)}>Previous</button>
      <span>Page {data.page + 1} of {data.totalPages}</span>
      <button disabled={!data.hasNext} onClick={() => onPage(data.page + 1)}>Next</button>
    </nav>
  );
}
```

`page` is **zero-based** on the wire and one-based for humans. Convert once, here.

### SearchPage — wiring it up

```jsx
// src/pages/public/SearchPage.jsx
import { useSearchParams } from 'react-router-dom';
import { parseQuery, toSearchParams, withChange, toggleFilter } from '@/lib/search-params';
import { useMediaSearch } from '@/hooks/useMediaSearch';

export default function SearchPage() {
  const [searchParams, setSearchParams] = useSearchParams();
  const query = parseQuery(searchParams);
  const { data, loading, error } = useMediaSearch(query);

  const apply = (next) => setSearchParams(toSearchParams(next));

  return (
    <main>
      <SearchBar q={query.q} source="platform" onSubmit={(p) => apply(withChange(query, p))} />

      <TabBar
        counts={data?.counts}
        active={query.type}
        onChange={(type) => apply(withChange(query, { type }))}
      />

      <SortSelect
        value={data?.sort ?? query.sort}
        onChange={(sort) => apply(withChange(query, { sort }))}
      />

      <div className="layout">
        <RefinePanel
          facets={data?.facets}
          filters={query.filters}
          onToggle={(param, value) => apply(toggleFilter(query, param, value))}
        />
        <div>
          <ResultGrid data={data} loading={loading} error={error} onRetry={() => apply(query)} />
          <Pagination data={data} onPage={(page) => apply({ ...query, page })} />
        </div>
      </div>
    </main>
  );
}
```

Note `value={data?.sort ?? query.sort}` on the sort control. The server **echoes the sort it
actually applied**, which defaults to `relevance` with a keyword and `newest` without one. Reading
it back from the response keeps the dropdown honest instead of showing a blank while the results
are clearly ordered by something.

---

## Step 5 — the detail page

One route serves all four kinds:

```jsx
// src/pages/public/MediaDetailPage.jsx  —  route: /item/:type/:code
import { useParams } from 'react-router-dom';
import { getMediaItem, payloadOf } from '@/services/media-search';

export default function MediaDetailPage() {
  const { type, code } = useParams();
  const [item, setItem] = useState(null);
  const [status, setStatus] = useState('loading');

  useEffect(() => {
    const controller = new AbortController();
    setStatus('loading');
    getMediaItem(type, code, { signal: controller.signal })
      .then((res) => { setItem(res); setStatus('ready'); })
      .catch((err) => {
        if (controller.signal.aborted) return;
        setStatus(err?.response?.status === 404 ? 'missing' : 'error');
      });
    return () => controller.abort();
  }, [type, code]);

  if (status === 'loading') return <DetailSkeleton />;
  if (status === 'missing') return <NotFound />;
  if (status === 'error')   return <ErrorState />;

  const payload = payloadOf(item);        // the full GuestAudioDTO / VideoDTO / …

  return (
    <article>
      <Header hit={item.item} />           {/* the flat card — no branching needed */}
      <MediaViewer type={item.type} payload={payload} />
      <Metadata type={item.type} payload={payload} />
      {item.related?.length > 0 && (
        <section aria-label="More from this collection">
          {item.related.map((hit) => (
            <ResultCard key={`${hit.type}:${hit.code}`} hit={hit} />
          ))}
        </section>
      )}
    </article>
  );
}
```

- `item.item` is the same flat card shape the search returns, so the page header, breadcrumbs and
  share metadata need no per-kind code.
- `payloadOf(item)` is the complete kind-specific DTO — byte-for-byte what
  `GET /api/guest/audios/{code}` returns — so any component already bound to `GuestAudioDTO` works
  unchanged. Its fields are listed in [`06-media.md`](./06-media.md).
- **404 means "not available"**, not "wrong URL". Unknown code, unknown type and a non-public item
  are deliberately indistinguishable. Render one honest "not available" page for all three.
- Pass `{ related: false }` on a page that does not show the rail — it skips loading the rest of
  the project.

---

## Step 6 — media URLs and players

`mediaUrl` and `thumbnailUrl` are **host-relative** paths like `/api/guest/audio/AUD-001/stream`.
They are not S3 URLs and must not be treated as absolute.

```js
import { resolveMediaUrl } from '@/lib/media-url';

<img   src={resolveMediaUrl(hit.thumbnailUrl)} alt="" loading="lazy" />
<audio src={resolveMediaUrl(payload.audioFileUrl)} controls preload="metadata" />
<video src={resolveMediaUrl(payload.videoFileUrl)} controls preload="metadata" playsInline />
```

`resolveMediaUrl` joins against the API **origin**, not `baseURL` — `baseURL` already ends in
`/api`, and joining against it yields `/api/api/...`. If the website project has no such helper,
write one; do not concatenate `baseURL + path` by hand.

Never send an `Authorization` header with these. A browser cannot attach headers to `<img src>`
anyway, which is exactly why `/api/guest/**` is anonymous. The byte proxies answer `Range` requests
with `206`, so seeking works in the native players; see [`07-streaming.md`](./07-streaming.md).

Thumbnails by kind, so you know what you are getting:

| Kind | `thumbnailUrl` is | Fallback |
|---|---|---|
| `image` | the image itself | the person's portrait |
| `text` | the cover, **only when one exists** | the person's portrait |
| `audio`, `video` | the project person's portrait | absent — render a kind icon |

Because sounds and videos often have no picture at all, `thumbnailUrl` can be absent. Design the
card for that case first, not as an afterthought.

---

## Autocomplete

Use the lighter, existing endpoint for the dropdown — not a full search on every keystroke:

```js
export async function suggest(q, signal) {
  const { data } = await apiClient.get('/guest/suggest', {
    params: { q, limit: 8 }, signal,
  });
  return data;
}
```

Debounce **300 ms**, abort the previous request, and require at least 2 characters. Run the full
`/guest/media/search` only on submit or when the visitor picks a suggestion. Shapes and behaviour
are in [`04-discovery.md`](./04-discovery.md).

---

## Kurdish, Arabic and RTL

The archive is bilingual, and a search page that ignores this looks broken to the people it is for.

- `title` may be Latin or Kurdish depending on the record; `titleInCentralKurdish` is Arabic-script
  Kurdish; `romanizedTitle` is always Latin.
- Use `dir="auto"` on any element rendering an archive-supplied string. The browser then picks the
  direction from the first strong character, per field, which is the correct behaviour for a mixed
  list — a global `dir="rtl"` is not.
- Set `lang="ckb"` on Central Kurdish text so fonts and screen readers resolve correctly.
- Numbers, dates and counts belong in the page's own locale: `n.toLocaleString(locale)`.
- Punctuation between two languages goes wrong silently. Prefer separate elements over
  `` `${title} — ${subtitle}` ``.

```jsx
<h3 dir="auto">{hit.title}</h3>
{hit.titleInCentralKurdish && (
  <p dir="auto" lang="ckb">{hit.titleInCentralKurdish}</p>
)}
```

---

## Error handling

Branch on the `error` **code**, never on the message and never on the status alone. The envelope is
documented in [`02-errors.md`](./02-errors.md).

```js
function messageFor(err) {
  if (err.name === 'CanceledError' || err.code === 'ERR_CANCELED') return null;  // not an error
  if (!err.response) return 'Cannot reach the archive. Check your connection.';
  if (err.response.status === 404) return 'This item is not available.';
  if (err.response.status >= 500) return 'The archive is having trouble. Try again shortly.';
  return err.response.data?.message ?? 'Something went wrong.';
}
```

These two endpoints introduce **no new error codes** and need no token, so there is no `401` path
to handle and nothing for the auth interceptor to react to. What they do have is a set of
*non*-errors that look like errors until you know them:

| Situation | What comes back | Show |
|---|---|---|
| Nothing matched | `200`, `empty: true`, counts all zero | The empty state, with spelling hints |
| Bad `dateFrom` / `decade` | `200`, filter silently ignored | Nothing — but validate in the UI so the visitor is not confused |
| Unknown `type` value | `200`, search widens to all kinds | Nothing |
| `size` over 100 | `200`, clamped to 100 | Nothing — clamp client-side too |
| `page` past the end | `200`, empty `content`, correct `totalElements` | Send the visitor back to page 0 |

---

## Performance checklist

- [ ] Debounce autocomplete at 300 ms; never debounce-search the full endpoint on keystroke.
- [ ] Abort superseded requests **and** guard with a request counter (Step 3).
- [ ] `loading="lazy"` and `decoding="async"` on every result thumbnail.
- [ ] Reserve the thumbnail box with a fixed aspect ratio so the grid does not shift as images land.
- [ ] Keep the previous page visible while the next one loads.
- [ ] Cache responses client-side, keyed on the full query string; the server does not cache these.
- [ ] Ask for `include=full` only where the extra fields are actually rendered — it roughly triples
      the payload.
- [ ] `size=24` for a grid; going to 100 costs payload for rows nobody scrolls to.
- [ ] Prefetch the detail response on card hover if the detail page feels slow.

---

## Accessibility checklist

- [ ] `role="search"` on the form; a real `<label>` for the input, visually hidden if need be.
- [ ] Tabs use `role="tablist"` / `role="tab"` with `aria-selected`.
- [ ] Announce result counts in a live region: `<p role="status">41 results for “Hasan Zirak”</p>`.
- [ ] `aria-busy` on the grid while loading.
- [ ] Decorative thumbnails take `alt=""`; the link text is the accessible name.
- [ ] Facet checkboxes are grouped in `<fieldset>` with a `<legend>`.
- [ ] Pagination is a `<nav>` with an accessible label; disabled buttons stay focusable-adjacent.
- [ ] The whole page is operable by keyboard, in a sensible tab order.

---

## QA matrix

Walk these before calling it done.

| # | Do this | Expect |
|---|---|---|
| 1 | Search `Hasan Zirak` | Results from more than one kind, ranked, tab counts populated |
| 2 | Switch to Sounds | The list narrows; **every tab count stays the same** |
| 3 | Go to page 2, then change the keyword | You land on page 1 of the new query, not an empty page 7 |
| 4 | Tick a Language facet | The list narrows; the URL gains `?language=…` |
| 5 | Tick a Person facet | The URL gains `personCode=PER-…`, not the person's name |
| 6 | Copy the URL into a new tab | Identical page — query, tab, filters, page |
| 7 | Press the back button | The previous state returns, and the input reflects it |
| 8 | Search a nonsense string | Empty state with hints, not a spinner and not an error |
| 9 | Type fast, then stop | Only the last request's results render |
| 10 | Open an audio result | Detail page plays; seeking in the player works |
| 11 | Open a photo, then a file | Same route, same header, correct viewer for each |
| 12 | Visit `/item/audio/DOES-NOT-EXIST` | "Not available" page, no crash |
| 13 | Search a Kurdish term | Kurdish text renders right-to-left, Latin text stays left-to-right |
| 14 | Throttle to Slow 3G | Skeletons, then results; the old page stays until the new one lands |
| 15 | Tab through the whole page | Everything reachable, focus visible throughout |

---

## Common mistakes

| Mistake | What you see | Fix |
|---|---|---|
| `apiClient.get('/api/guest/media/search')` | `404` on `/api/api/...` | `baseURL` already ends in `/api` — drop the prefix |
| Missing `paramsSerializer: { indexes: null }` | Tag and subject filters do nothing, no error | Add it — Spring needs `?tag=a&tag=b` |
| Person facet sent as `label` | Empty results, no error | `persons` and `projects` send `code`; everything else sends `label` |
| `key={hit.id}` in the merged list | Cards swap content when paging | `key={`${hit.type}:${hit.code}`}` — ids are per-table |
| Filter change without resetting `page` | Empty page after refining | Route every change through `withChange` |
| Reading counts from `totalElements` | Tab numbers change when tabs change | Tabs read `counts`; `totalElements` is the selected kinds only |
| Branching the card on `type` | Four card components, three of them stale | One card; kind-specific fields are simply absent |
| `baseURL + hit.mediaUrl` | `/api/api/guest/...`, broken media | `resolveMediaUrl()` joins against the **origin** |
| Treating `404` as a routing bug | "Page not found" for a private record | 404 also means not public — say "not available" |
| Sending `Authorization` to a byte proxy | Nothing, or a CORS preflight failure | These endpoints are anonymous by design |
| Full search on every keystroke | Slow page, wasted requests, trending log noise | Autocomplete on `/guest/suggest`; full search on submit |
| Assuming `thumbnailUrl` exists | Broken image icons across the sounds tab | It is often absent — fall back to a kind icon |

---

## Related

- [`10-website-search.md`](./10-website-search.md) — the API reference these components consume
- [`06-media.md`](./06-media.md) — the full per-kind DTO fields behind `include=full` and the
  detail payload
- [`07-streaming.md`](./07-streaming.md) — `Range`, `206`, `ETag`, and where the media URLs come from
- [`04-discovery.md`](./04-discovery.md) — `/suggest` for the autocomplete, `/feed` for a
  keyword-less browse
- [`02-errors.md`](./02-errors.md) — the `ApiErrorResponse` envelope and the closed `ErrorCode` set
- [`01-conventions.md`](./01-conventions.md) — paging, date formats, omitted `null` fields, CORS
- [`../FRONTEND_INTEGRATION.md`](../FRONTEND_INTEGRATION.md) — the axios client, token storage,
  `resolveMediaUrl`, CORS allowlist and the local dev setup
