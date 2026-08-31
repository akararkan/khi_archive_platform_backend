# Frontend Guide — Media URLs (Image / Audio / Video / Text)

> Companion to `PRIVATE_MEDIA_STREAMING.md`. That doc explains how the proxy
> streaming works server-side; this one is the checklist for wiring the
> frontend to it correctly, written after tracing a real 401 back to a
> frontend URL mistake plus two backend bugs (fixed) that were making things
> worse.

## 1. Two URL families — use the right one per page

| Context | URL prefix | Auth | Use for |
|---|---|---|---|
| Public/guest pages | `/api/guest/{image,audio,video,text}/...` | none | any page a logged-out visitor can reach |
| Admin/employee dashboard | `/api/{image,audio,video,text}/...` | JWT required | logged-in-only screens (edit forms, trash/restore preview) |

**A 401 on a public page almost always means the admin prefix was used
without a logged-in user.** Every `imageFileUrl` / `audioFileUrl` /
`videoFileUrl` / `textFileUrl` / `coverImageUrl` field returned by
`/api/guest/**` endpoints (`/api/guest/feed`, `/api/guest/images`,
`/api/guest/audios`, `/api/guest/videos`, `/api/guest/texts`, and their
`/{code}` detail routes) already comes back as the correct guest path — just
use it as-is, don't rewrite it, don't swap in `/api/items` data for a public
page.

`GET /api/items` is an **admin-only** aggregator (requires all four
`*:read` authorities) and its rows always carry the admin-shaped URLs
(`/api/image/{code}/view`, etc.), even for records that are otherwise
public. It's for the dashboard, not the public site.

## 2. Public pages — plain tags, no token needed

```jsx
const API_BASE = import.meta.env.VITE_API_BASE_URL;
const mediaUrl = (path) => (path?.startsWith('http') ? path : `${API_BASE}${path}`);

<img src={mediaUrl(image.imageFileUrl)} loading="lazy" />
<audio controls preload="metadata" src={mediaUrl(audio.audioFileUrl)} />
<video controls preload="metadata" playsInline src={mediaUrl(video.videoFileUrl)} />
<a href={mediaUrl(text.textFileUrl)}>Open book</a>
<img src={mediaUrl(text.coverImageUrl)} />
```

These all support Range seeking, ETag caching, etc. — nothing else needed.

## 3. Admin/dashboard pages — token required, plain `<img>`/`<video>` won't work

`<img src>`, `<audio src>`, `<video src>` cannot send an `Authorization`
header, so a raw `src` pointed at `/api/image/{code}/view` will always 401
for a logged-in user too. Fetch as a blob and hand the browser an object URL
instead:

```js
function useAdminMediaUrl(apiPath, token) {
  const [blobUrl, setBlobUrl] = useState(null);
  useEffect(() => {
    if (!apiPath || !token) return;
    let objectUrl;
    fetch(`${API_BASE}${apiPath}`, { headers: { Authorization: `Bearer ${token}` } })
      .then(res => { if (!res.ok) throw new Error(`${res.status}`); return res.blob(); })
      .then(blob => setBlobUrl(objectUrl = URL.createObjectURL(blob)))
      .catch(console.error);
    return () => objectUrl && URL.revokeObjectURL(objectUrl);
  }, [apiPath, token]);
  return blobUrl;
}
```

Use this for admin previews of image/audio/video/text — `item.imageFileUrl`
etc. from `/api/items` or the admin `/api/{type}` endpoints are already the
`/api/{type}/{code}/...` (JWT) paths, pass them straight into the hook.

> Large admin video previews are slow with the blob approach since the whole
> chunk has to download before playback starts. Acceptable for admin, not
> for the public site (which already streams properly via Range requests).

## 4. Error handling — 404 now means "genuinely missing," not "server crashed"

Previously, a missing/corrupted S3 object (bad data, not a client mistake)
returned a generic 500. It now correctly returns 404 with a clear message
(`"Image not available for {code}"`, `"Video not available for {code}"`,
`"Audio not available for {code}"`, `"Book file not available for {code}"`,
`"Cover image not available for {code}"`). Handle it as: show a
placeholder/broken-media state, don't retry, don't treat it as a transient
error. A real 500 (network/S3 outage) is still worth a retry-with-backoff.

```js
img.onerror = () => setBroken(true); // works for 404 same as before — no client change needed
```

## 5. Filenames — no action needed, just FYI

`Content-Disposition` now correctly preserves non-Latin (Kurdish/Arabic)
filenames instead of collapsing them to underscores, via standard
`filename*=UTF-8''...` encoding alongside the ASCII `filename=` fallback.
Browsers already handle this natively — nothing to change on your end unless
you're manually parsing that header.
