# Guest Frontend API Guide

This guide is for the public guest pages only.

The guest public media page must not use `/api/guest/feed`. Use the existing
get-all media APIs and render them in this fixed order:

`photos -> sounds -> videos -> texts`

## Main Public Media Page

Call these APIs, in parallel if possible:

```http
GET /api/guest/images?page=0&size=12
GET /api/guest/audios?page=0&size=12
GET /api/guest/videos?page=0&size=12
GET /api/guest/texts?page=0&size=12
```

Each endpoint returns a Spring `Page`:

```js
{
  content: [],
  number,
  size,
  totalElements,
  totalPages,
  numberOfElements,
  first,
  last,
  empty
}
```

Use `page.totalElements` for the real count. Do not use
`page.content.length` as the total; that is only the number loaded on the
current page.

The backend now returns only visible guest media:

```text
media.isPublic === true
project.isVisibleToPublic === true
removedAt === null
```

## Type Selection

The frontend controls type selection by deciding which endpoints to call.

| UI label | API |
| --- | --- |
| Photos | `/api/guest/images` |
| Sounds | `/api/guest/audios` |
| Videos | `/api/guest/videos` |
| Texts | `/api/guest/texts` |

If the user selects only photos and sounds, call only:

```http
GET /api/guest/images
GET /api/guest/audios
```

Keep the render order the same: photos, sounds, videos, texts.

## Common Filters

Send the same common filters to every selected media endpoint:

| UI filter | Query param |
| --- | --- |
| Search text | `q` |
| Project | `projectCode` |
| Category | `categoryCode` |
| Person | `personCode` |
| Language | `language` |
| Dialect | `dialect` |
| Region | `region` |
| Subject | `subject` repeated |
| Genre | `genre` repeated |
| Tag | `tag` repeated |
| Keyword | `keyword` repeated |
| Date from | `dateFrom` |
| Date to | `dateTo` |
| Sort field | `sortBy` |
| Sort direction | `sortDirection` |

Sort values:

```text
sortBy=relevance | date | datePublished | title
sortDirection=asc | desc
```

When the user changes any filter, reset `page` to `0`.

## Frontend Request Helper

Use repeated params for arrays:

```js
const MEDIA_ENDPOINTS = {
  image: "/api/guest/images",
  audio: "/api/guest/audios",
  video: "/api/guest/videos",
  text: "/api/guest/texts",
};

const MEDIA_ORDER = ["image", "audio", "video", "text"];

function buildMediaUrl(kind, filters) {
  const params = new URLSearchParams();

  params.set("page", String(filters.page ?? 0));
  params.set("size", String(filters.size ?? 12));

  if (filters.q) params.set("q", filters.q);
  if (filters.projectCode) params.set("projectCode", filters.projectCode);
  if (filters.categoryCode) params.set("categoryCode", filters.categoryCode);
  if (filters.personCode) params.set("personCode", filters.personCode);
  if (filters.language) params.set("language", filters.language);
  if (filters.dialect) params.set("dialect", filters.dialect);
  if (filters.region) params.set("region", filters.region);
  if (filters.dateFrom) params.set("dateFrom", filters.dateFrom);
  if (filters.dateTo) params.set("dateTo", filters.dateTo);
  if (filters.sortBy) params.set("sortBy", filters.sortBy);
  if (filters.sortDirection) params.set("sortDirection", filters.sortDirection);

  for (const value of filters.subjects ?? []) params.append("subject", value);
  for (const value of filters.genres ?? []) params.append("genre", value);
  for (const value of filters.tags ?? []) params.append("tag", value);
  for (const value of filters.keywords ?? []) params.append("keyword", value);

  return `${MEDIA_ENDPOINTS[kind]}?${params.toString()}`;
}

async function loadGuestPublicMedia(filters) {
  const selectedKinds = filters.types?.length ? filters.types : MEDIA_ORDER;
  const selected = new Set(selectedKinds);

  const entries = await Promise.all(
    MEDIA_ORDER.map(async (kind) => {
      if (!selected.has(kind)) return [kind, null];
      const response = await fetch(buildMediaUrl(kind, filters));
      return [kind, await response.json()];
    })
  );

  return entries.map(([kind, page]) => ({
    kind,
    page: page ?? {
      content: [],
      totalElements: 0,
      totalPages: 0,
      number: filters.page ?? 0,
      size: filters.size ?? 12,
      first: true,
      last: true,
      empty: true,
    },
  }));
}
```

## Rendering Cards

Normalize cards by section kind:

```js
function normalizeMediaCard(kind, item) {
  const byKind = {
    image: {
      code: item.imageCode,
      fileUrl: item.imageFileUrl,
      title: item.originalTitle || item.alternativeTitle || item.titleInCentralKurdish || item.romanizedTitle,
      path: `/guest/images/${item.imageCode}`,
    },
    audio: {
      code: item.audioCode,
      fileUrl: item.audioFileUrl,
      title: item.originTitle || item.alterTitle || item.centralKurdishTitle || item.romanizedTitle,
      path: `/guest/audios/${item.audioCode}`,
    },
    video: {
      code: item.videoCode,
      fileUrl: item.videoFileUrl,
      title: item.originalTitle || item.alternativeTitle || item.titleInCentralKurdish || item.romanizedTitle,
      path: `/guest/videos/${item.videoCode}`,
    },
    text: {
      code: item.textCode,
      fileUrl: item.textFileUrl,
      title: item.originalTitle || item.alternativeTitle || item.titleInCentralKurdish || item.romanizedTitle,
      path: `/guest/texts/${item.textCode}`,
    },
  }[kind];

  return { kind, ...byKind, item };
}

const sections = await loadGuestPublicMedia(filters);

const cardsForOneGrid = sections.flatMap((section) =>
  section.page.content.map((item) => normalizeMediaCard(section.kind, item))
);

const totalVisibleResults = sections.reduce(
  (sum, section) => sum + section.page.totalElements,
  0
);
```

For section counts:

```js
const photoCount = sections.find((s) => s.kind === "image").page.totalElements;
const soundCount = sections.find((s) => s.kind === "audio").page.totalElements;
const videoCount = sections.find((s) => s.kind === "video").page.totalElements;
const textCount = sections.find((s) => s.kind === "text").page.totalElements;
```

## Sidebar Filter Counts

Use this endpoint once on page load, and refresh after major data/admin changes:

```http
GET /api/guest/facets
```

Use these counts for media filter buttons:

```js
facets.mediaTypes.images
facets.mediaTypes.audios
facets.mediaTypes.videos
facets.mediaTypes.texts
```

Use these arrays for checkbox lists:

```js
facets.categories // { code, label, count }
facets.persons
facets.languages
facets.dialects
facets.regions
facets.genres
facets.tags
facets.keywords
```

## Trending

Use this endpoint for trending sections and badges:

```http
GET /api/guest/trending
```

Good frontend uses:

| Data | UI |
| --- | --- |
| `trendingItems` | main trending row/carousel |
| `trendingByType.image` | trending photos |
| `trendingByType.audio` | trending sounds |
| `trendingByType.video` | trending videos |
| `trendingByType.text` | trending texts |
| `topSearches` | popular search chips |

The media pages already include `trending`, `trendingRank`, and
`trendingScore` on each card.

## Search Box

For autocomplete while the user types:

```http
GET /api/guest/suggest?q=zirak&limit=10
```

For a search-results preview page that includes projects, categories, persons,
and media sections:

```http
GET /api/guest/search?q=zirak&perSection=10
```

For the main browsable media grid, call the four media get-all APIs, not
`/search`.

## Projects, Categories, Persons

Use these for browse pages or detail side panels:

```http
GET /api/guest/projects?page=0&size=50
GET /api/guest/projects/{projectCode}
GET /api/guest/projects/{projectCode}/media

GET /api/guest/categories?page=0&size=100
GET /api/guest/categories/{categoryCode}
GET /api/guest/categories/{categoryCode}/projects

GET /api/guest/persons?page=0&size=50
GET /api/guest/persons/{personCode}
GET /api/guest/persons/{personCode}/projects
```

## Dedicated Media Pages

Use these same endpoints for dedicated media pages and direct detail URLs:

```http
GET /api/guest/images?page=0&size=50
GET /api/guest/images/{imageCode}

GET /api/guest/audios?page=0&size=50
GET /api/guest/audios/{audioCode}

GET /api/guest/videos?page=0&size=50
GET /api/guest/videos/{videoCode}

GET /api/guest/texts?page=0&size=50
GET /api/guest/texts/{textCode}
```

## Removed APIs

Do not use these endpoints:

```http
GET /api/guest/feed
GET /api/guest/results
```

The frontend should use the four get-all media endpoints for guest browsing and
filtering.
