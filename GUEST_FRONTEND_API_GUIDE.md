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

`types=image,audio,video,text` also works, but repeated params are clearer.

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

## Feed Response Handling

`/feed` returns one grouped object, not a Spring `Page`.

Use:

```js
const sections = [
  data.images, // photos
  data.audios, // sounds
  data.videos,
  data.texts,
];

const totalItems = data.totalElements;
const currentPage = data.page;
const hasNext = data.hasNext;
const hasPrevious = data.hasPrevious;

const imageCount = data.images.totalElements;
const audioCount = data.audios.totalElements;
const videoCount = data.videos.totalElements;
const textCount = data.texts.totalElements;
```

Do not use `section.content.length` as the total result count. That is only the
number of cards currently visible in that section. Use
`section.totalElements` for the real filtered total.

Response shape:

```js
{
  order: ["image", "audio", "video", "text"],
  images: {
    kind: "image",
    content: [/* GuestImageDTO */],
    totalElements,
    totalPages,
    numberOfElements,
    first,
    last,
    empty
  },
  audios: {
    kind: "audio",
    content: [/* GuestAudioDTO */],
    totalElements,
    totalPages,
    numberOfElements,
    first,
    last,
    empty
  },
  videos: { kind: "video", content: [/* GuestVideoDTO */] },
  texts: { kind: "text", content: [/* GuestTextDTO */] },
  totalElements,
  page,
  size,
  hasNext,
  hasPrevious
}
```

`size` is applied per section. For example, `size=12` can return up to
12 photos, 12 sounds, 12 videos, and 12 texts.

If a type is not selected, its section still exists but has empty `content` and
`totalElements: 0`.

Cards are full media DTOs. Use the section kind to read the right code and file
field:

```js
function normalizeFeedCard(kind, item) {
  const config = {
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

  return { kind, ...config, item };
}

const cardsForOneGrid = sections.flatMap((section) =>
  section.content.map((item) => normalizeFeedCard(section.kind, item))
);
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

Project code rule:

- person-linked projects: frontend may send `PERSONCODE-PROJ-######`
- non-person projects: frontend should send `PROJECTPREFIX-PROJ-######`

Use the code prefix before `_PROJ` / `-PROJ` as the real project identifier.
Example: `DENG-PROJ-000004` → prefix `DENG`.

For non-person media items, the code prefix uses only that project-code prefix:

- `DENG_IMG_RAW_V1_Copy(1)_000001`
- `DENG_AUD_RAW_V1_Copy(1)_000001`

Create flow:

1. Frontend chooses project name and category.
2. If there is no person, frontend builds the `projectCode` itself, such as `DENG-PROJ-000004`.
3. Frontend sends that `projectCode` with the create request.
4. Backend stores the project and uses the same code in all later media links.

## Recommended Frontend Rendering

Use the same visual language everywhere so the app feels consistent.

For media lists, each row/card should show:

- media type badge: image, audio, video, or text
- media code
- title
- project code
- project name
- person name when available, or a muted `No person`
- category chips
- public/private badge

For the unified `/api/items` list, the backend already returns:

- `projectCode`
- `projectName`
- `personCode`
- `personName`
- `categoryCodes`
- `isPublic`
- the full media DTO in `audio`, `video`, `image`, or `text`

So on the internal/admin items page, show the same chips and badges there too.
Each item row should still display its `categoryCodes` and the project identity.

For the guest media endpoints, the DTO already includes:

- `projectCode`
- `projectName`
- `person`
- `categories`

So the frontend can render category chips without extra API calls.

Suggested visual hierarchy for each media card:

1. top row: media type badge + code
2. second row: title
3. third row: project chip + person chip
4. fourth row: category chips
5. last row: visibility and trending badges

Color suggestions:

- image badge: green
- audio badge: orange
- video badge: red
- text badge: indigo
- project code chip: blue
- person chip: purple when linked, gray when empty
- category chips: neutral or lightly tinted, one stable color per category if you want visual grouping

For project lists, each project card should show:

- project name as the main title
- project code as a smaller chip
- person name if present
- category chips from `categories[]`
- media counts from `mediaCounts`
- trending badge when `isTrending` is true

For untitled projects, show the project code clearly because it is the primary identifier for media creation and item filtering.

For person-linked projects, show the person name first, then the project code.

If you want a very clean UI rule:

- project name = user-friendly label
- project code = stable technical identifier
- category chips = classification
- person chip = ownership/association
- media code = immutable item identifier

## Media-Specific Pages

Use `/feed` for the main mixed public page. Use media-specific endpoints only
for dedicated media pages, direct detail URLs, or a fresh single-item reload.

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

The grouped `/feed` response already contains full media DTOs for its cards.

## Removed API

Do not use this endpoint:

```http
GET /api/guest/results
```

It was removed because it duplicated `/api/guest/feed`. The frontend should use
`/api/guest/feed` for all mixed media guest browsing and filtering.
