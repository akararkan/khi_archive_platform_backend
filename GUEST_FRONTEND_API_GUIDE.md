# Guest Frontend API Guide

This guide is for the public guest pages only. Guest users should see public media
from public projects, ordered as:

`photos -> sounds -> videos -> texts`

## Main Public Feed

Use this endpoint for the guest browse/search page:

```http
GET /api/guest/feed
```

Default request:

```http
GET /api/guest/feed?page=0&size=50
```

With all media types explicitly selected:

```http
GET /api/guest/feed?page=0&size=50&types=image&types=audio&types=video&types=text
```

Use these type values:

| UI label | API value |
| --- | --- |
| Photos | `image` |
| Sounds | `audio` |
| Videos | `video` |
| Texts | `text` |

Supported filters:

| UI filter | Query param |
| --- | --- |
| Search text | `q` |
| Media type | `types` repeated |
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

## Page Response Handling

`/feed` returns a Spring `Page`.

Use:

```js
const items = data.content;
const totalItems = data.totalElements;
const currentPage = data.number;
const totalPages = data.totalPages;
const hasNext = !data.last;
const hasPrevious = !data.first;
```

Do not use `data.content.length` as the total result count. That is only the
number of cards in the current page.

Each feed item has:

```js
{
  kind,          // image | audio | video | text
  id,
  code,
  title,
  projectCode,
  projectName,
  personCode,
  personName,
  personMediaPortrait,
  categories,
  language,
  dialect,
  region,
  dateCreated,
  datePublished,
  fileUrl,
  score,
  trending,
  trendingRank,
  trendingScore
}
```

Route cards by `kind`:

```js
const detailPath = {
  image: `/guest/images/${item.code}`,
  audio: `/guest/audios/${item.code}`,
  video: `/guest/videos/${item.code}`,
  text: `/guest/texts/${item.code}`,
}[item.kind];
```

## Building Query Params

Use repeated params for arrays:

```js
function buildGuestFeedUrl(filters) {
  const params = new URLSearchParams();

  params.set("page", String(filters.page ?? 0));
  params.set("size", String(filters.size ?? 50));

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

  for (const type of filters.types ?? []) params.append("types", type);
  for (const value of filters.subjects ?? []) params.append("subject", value);
  for (const value of filters.genres ?? []) params.append("genre", value);
  for (const value of filters.tags ?? []) params.append("tag", value);
  for (const value of filters.keywords ?? []) params.append("keyword", value);

  return `/api/guest/feed?${params.toString()}`;
}
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

Cards from `/feed` already include `trending`, `trendingRank`, and
`trendingScore`, so you do not need to call `/trending` for every card.

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

For the main browsable media grid, use `/api/guest/feed?q=...`, not `/search`.

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

## Media-Specific Pages

Use `/feed` for the main mixed public page. Use media-specific endpoints only
when the page needs fields unique to one media type.

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

Use detail endpoints after a card click when the UI needs the full DTO for that
media item.

## Removed API

Do not use this endpoint:

```http
GET /api/guest/results
```

It was removed because it duplicated `/api/guest/feed`. The frontend should use
`/api/guest/feed` for all mixed media guest browsing and filtering.
