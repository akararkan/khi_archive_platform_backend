# Private Media Streaming — Full Implementation Guide

> **What this document covers:**  
> Every file that was changed or created to implement private, proxied media
> streaming for **Audio**, **Video**, and **Images**.  No S3 URL ever leaves
> the server again.  The browser only ever receives bytes through your own API.

---

## Table of Contents

1. [The Problem — What Was Wrong Before](#1-the-problem)
2. [The Solution — The Proxy Streaming Pattern](#2-the-solution)
3. [File-by-File Changes](#3-file-by-file-changes)
   - [S3Service.java — New Streaming Methods](#31-s3servicejava)
   - [AudioStreamAPI.java — NEW FILE](#32-audiostreamapijava--new-file)
   - [VideoStreamAPI.java — NEW FILE](#33-videostreamapijava--new-file)
   - [ImageStreamAPI.java — NEW FILE](#34-imagestreamapijava--new-file)
   - [GuestMapper.java — URLs replaced](#35-guestmapperjava)
   - [AudioService.java — URL replaced](#36-audioservicejava)
   - [VideoService.java — URL replaced](#37-videoservicejava)
   - [ImageService.java — URL replaced](#38-imageservicejava)
   - [GuestAudioDTO / GuestVideoDTO / GuestImageDTO — Comments updated](#39-dto-comment-updates)
   - [SecurityConfig.java — Comment updated](#310-securityconfigjava)
4. [How the Streaming Works — Step by Step](#4-how-streaming-works)
   - [Audio streaming with Range requests](#41-audio-range-requests)
   - [Video chunk-based streaming](#42-video-chunk-streaming)
   - [Image with ETag caching](#43-image-etag-caching)
5. [Response Headers — What and Why](#5-response-headers)
6. [Security Model](#6-security-model)
7. [Frontend Integration Guide](#7-frontend-integration-guide)
   - [Setup — API Base URL](#71-setup)
   - [Audio Player](#72-audio-player)
   - [Video Player](#73-video-player)
   - [Image Display](#74-image-display)
   - [Admin Panel (JWT required)](#75-admin-panel)
8. [API Endpoint Reference](#8-api-endpoint-reference)
9. [What Was NOT Changed](#9-what-was-not-changed)
10. [Phase 2 Roadmap — HLS + Image Tiling](#10-phase-2-roadmap)

---

## 1. The Problem

Before this change, when a user called any media API, the response contained
a **direct public AWS S3 URL**:

```json
{
  "audioCode": "AUD-001",
  "audioFileUrl": "https://your-bucket.s3.eu-west-1.amazonaws.com/khi-archive-platform-folders/audios/AUD-001/abc123-song.mp3"
}
```

### Why that is dangerous

| Risk | Explanation |
|------|-------------|
| **Direct download** | Anyone who sees the URL can paste it in a browser and download the full file |
| **Permanent link** | The S3 URL never expires — it works forever, even after the user logs out |
| **No access control** | Once the URL leaks (browser history, logs, screenshots), there is no way to revoke it |
| **Bulk scraping** | An attacker can collect every URL from API responses and bulk-download your entire archive |
| **Bypasses all your auth** | Your JWT, your `removedAt` checks, your admin controls — all ignored if someone has the S3 URL |

The **only** media type that was correctly protected before was **Maqam audio**
(via `MaqamStreamAPI`). Everything else was exposed.

---

## 2. The Solution

**The Proxy Streaming Pattern** — every media byte passes through your API.

```
BEFORE (broken):
  Browser ──── GET /api/guest/audio/AUD-001 ────► Backend
  Backend ── returns { audioFileUrl: "https://s3.amazonaws.com/..." } ──► Browser
  Browser ──── GET https://s3.amazonaws.com/... ────────────────────────► S3 (direct!)
                                                         ◄── full file ───

AFTER (secure):
  Browser ──── GET /api/guest/audio/AUD-001 ────► Backend
  Backend ── returns { audioFileUrl: "/api/guest/audio/AUD-001/stream" } ──► Browser
  Browser ──── GET /api/guest/audio/AUD-001/stream ────► Backend
                                                          Backend ──► S3 (internal)
                                                          S3 ◄──► Backend (bytes only)
  Browser ◄──────────── audio bytes (206) ─────────────── Backend
```

The S3 URL **never reaches the browser**. The browser only sees your API path.

---

## 3. File-by-File Changes

### 3.1 `S3Service.java`

**Location:** `src/main/java/ak/dev/khi_archive_platform/S3Service.java`

**What changed:** Three new methods were added to support efficient streaming
without loading entire files into JVM heap.

#### New import added
```java
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
```

#### New method 1: `openStream(String key)`
```java
/**
 * Opens a streaming ResponseInputStream for the given S3 key.
 * Unlike downloadByUrl(), this does NOT buffer the full object —
 * the caller MUST close the returned stream after reading.
 * Suitable for large files (video, audio) where loading all bytes
 * into memory would be wasteful.
 */
public ResponseInputStream<GetObjectResponse> openStream(String key) {
    GetObjectRequest request = GetObjectRequest.builder()
            .bucket(bucket)
            .key(key)
            .build();
    return s3Client.getObject(request);
}
```

**Why:** `s3Client.getObject()` returns a streaming `InputStream` — S3 sends
bytes on demand. The old `getObjectAsBytes()` loads the **entire file into
memory** first, which would crash your server with a 2 GB video.

#### New method 2: `openStreamRange(String key, long start, long end)`
```java
/**
 * Opens a streaming ResponseInputStream with a byte-range request.
 * Only the bytes between start and end are fetched from S3.
 * The caller MUST close the returned stream after reading.
 */
public ResponseInputStream<GetObjectResponse> openStreamRange(String key, long start, long end) {
    GetObjectRequest request = GetObjectRequest.builder()
            .bucket(bucket)
            .key(key)
            .range("bytes=" + start + "-" + end)   // S3 Range GET
            .build();
    return s3Client.getObject(request);
}
```

**Why:** HTTP Range requests are how browsers seek inside audio/video.
When a user drags the progress bar to minute 45, the browser sends
`Range: bytes=52428800-54525951`. This method fetches **only those bytes**
from S3 — not the whole file.

#### New method 3: `getObjectSize(String key)`
```java
/**
 * Returns the size in bytes of an S3 object without downloading it.
 * Uses a HEAD request — zero data transfer.
 */
public long getObjectSize(String key) {
    HeadObjectResponse head = s3Client.headObject(
            HeadObjectRequest.builder().bucket(bucket).key(key).build());
    return head.contentLength() != null ? head.contentLength() : 0L;
}
```

**Why:** To respond correctly to a Range request, the server must tell the
browser `Content-Range: bytes 0-2097151/52428800` — it needs to know the
**total file size**. A `HEAD` request to S3 gets the size without downloading
any bytes.

---

### 3.2 `AudioStreamAPI.java` — NEW FILE

**Location:** `src/main/java/ak/dev/khi_archive_platform/platform/api/audio/AudioStreamAPI.java`

**What it does:** Proxies audio bytes through the backend with full HTTP Range
support. Two endpoints in one class.

```java
// Public — no auth, only non-deleted records
@GetMapping("/api/guest/audio/{audioCode}/stream")
public ResponseEntity<byte[]> streamPublic(
        @PathVariable String audioCode,
        @RequestHeader(value = HttpHeaders.RANGE, required = false) String rangeHeader) {

    Audio audio = audioRepository.findByAudioCodeAndRemovedAtIsNull(audioCode)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Audio not found"));
    return buildStreamResponse(audio, rangeHeader, true);
}

// Admin — requires JWT token (enforced by SecurityFilterChain)
// Also serves soft-deleted records so admins can preview before restoring
@GetMapping("/api/audio/{audioCode}/stream")
public ResponseEntity<byte[]> streamAuthenticated(
        @PathVariable String audioCode,
        @RequestHeader(value = HttpHeaders.RANGE, required = false) String rangeHeader) {

    Audio audio = audioRepository.findByAudioCode(audioCode)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Audio not found"));
    return buildStreamResponse(audio, rangeHeader, false);
}
```

#### Shared streaming logic
```java
private ResponseEntity<byte[]> buildStreamResponse(Audio audio, String rangeHeader, boolean isPublic) {
    String fileUrl = audio.getAudioFileUrl();   // internal S3 URL — never sent to browser
    String key = s3Service.extractKeyFromUrl(fileUrl);

    MediaType contentType = resolveContentType(fileUrl);
    long total = s3Service.getObjectSize(key);   // HEAD request — no download

    // Parse Range header (or full file if no Range sent)
    long[] range = parseRange(rangeHeader, total);
    long start = range[0];
    long end   = range[1];

    // Download ONLY the requested byte window from S3
    byte[] slice = downloadRange(key, start, end);

    boolean isRangeRequest = rangeHeader != null && !rangeHeader.isBlank();
    HttpHeaders headers = buildHeaders(audio, contentType, total, isPublic, isRangeRequest, start, end);
    headers.setContentLength(end - start + 1);

    return new ResponseEntity<>(slice, headers,
            isRangeRequest ? HttpStatus.PARTIAL_CONTENT : HttpStatus.OK);
}
```

#### Cache policy decision
```java
// Public content: CDN and browser can cache for 5 minutes — reduces server load.
// Admin content: never cache — admin may preview soft-deleted/private records.
headers.setCacheControl(isPublic ? "public, max-age=300" : "no-store, private");
```

#### Filename in Content-Disposition
```java
// Uses the real filename if available, falls back to "audio-AUD-001.mp3"
headers.set(HttpHeaders.CONTENT_DISPOSITION,
        "inline; filename=\"" + safeFilename(audio.getFileName(), audio.getAudioCode(), contentType) + "\"");
```

---

### 3.3 `VideoStreamAPI.java` — NEW FILE

**Location:** `src/main/java/ak/dev/khi_archive_platform/platform/api/video/VideoStreamAPI.java`

**What makes video different from audio:**

Video files can be gigabytes. Loading even a single 500 MB video into JVM heap
would cause an `OutOfMemoryError`. The video controller solves this with a
**2 MB default chunk** strategy:

```java
/** Default initial chunk for non-Range requests (2 MB). */
private static final long DEFAULT_CHUNK_BYTES = 2 * 1024 * 1024L;
```

```java
private long[] parseRange(String header, long total) {
    if (header == null || header.isBlank()) {
        // No Range header (first load): serve only the first 2 MB.
        // The browser will then issue Range requests for the rest as the user watches.
        long end = Math.min(DEFAULT_CHUNK_BYTES - 1, total - 1);
        return new long[]{0, end};
    }
    // ... parse the actual Range header ...
}
```

The controller **always returns `206 Partial Content`** — even on the first
request. This is the correct HTTP signal that tells the browser:

> "This is a seekable, range-capable stream. Come back with Range headers
> when you need more bytes."

```
First request (no Range header):
  Server → 206, bytes 0–2097151 / 524288000
  Browser learns: total=500 MB, I got the first 2 MB

User seeks to 45:00:
  Browser → Range: bytes=52428800-
  Server  → 206, bytes 52428800–54525951 / 524288000
  (only 2 MB fetched from S3, rest is untouched)
```

---

### 3.4 `ImageStreamAPI.java` — NEW FILE

**Location:** `src/main/java/ak/dev/khi_archive_platform/platform/api/image/ImageStreamAPI.java`

**What makes images different from audio/video:**

Images don't need Range requests (they're loaded whole), but they benefit
enormously from **caching**. The same image might be displayed hundreds of
times on list pages. The image controller adds **ETag support**:

#### ETag generation
```java
// A short SHA-1 hash of the imageCode serves as the ETag.
// It's stable — the image content never changes after upload.
String etag = "\"" + sha1Short(image.getImageCode()) + "\"";
```

#### 304 Not Modified — zero bytes transferred
```java
// If the browser sends If-None-Match: "9f3a2c" and it matches our ETag,
// return 304 immediately. No S3 download. No bytes sent. Zero cost.
if (etag.equals(ifNoneMatch)) {
    return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(etag).build();
}
```

#### Cache headers for images
```java
// Public: 1-hour CDN/browser cache. Images are immutable after upload.
// Admin: never cache — admin may preview soft-deleted/private images.
headers.setCacheControl(isPublic ? "public, max-age=3600" : "no-store, private");
```

**Result:**
```
1st load of page:   200 OK — image bytes sent (S3 → backend → browser)
2nd load same page: 304 Not Modified — 0 bytes, 0 S3 cost, instant display
After 1 hour:       200 OK — fresh copy fetched again
```

---

### 3.5 `GuestMapper.java`

**Location:** `src/main/java/ak/dev/khi_archive_platform/platform/service/guest/GuestMapper.java`

**What changed:** Three lines — the public S3 URL is replaced with the stream endpoint path.

```java
// BEFORE
.audioFileUrl(a.getAudioFileUrl())   // "https://bucket.s3.region.amazonaws.com/..."
.videoFileUrl(v.getVideoFileUrl())   // "https://bucket.s3.region.amazonaws.com/..."
.imageFileUrl(i.getImageFileUrl())   // "https://bucket.s3.region.amazonaws.com/..."

// AFTER
.audioFileUrl("/api/guest/audio/" + a.getAudioCode() + "/stream")
.videoFileUrl("/api/guest/video/" + v.getVideoCode() + "/stream")
.imageFileUrl("/api/guest/image/" + i.getImageCode() + "/view")
```

The `GuestMapper` is used by the public guest API (`/api/guest/**`).

---

### 3.6 `AudioService.java`

**Location:** `src/main/java/ak/dev/khi_archive_platform/platform/service/audio/AudioService.java`

**What changed:** One line in the `toResponse()` mapper method (used by admin API).

```java
// BEFORE (in toResponse() method, line ~565)
response.setAudioFileUrl(audio.getAudioFileUrl());
// returned: "https://bucket.s3.region.amazonaws.com/..."

// AFTER
response.setAudioFileUrl("/api/audio/" + audio.getAudioCode() + "/stream");
// returned: "/api/audio/AUD-001/stream"
```

Note the path difference: admin uses `/api/audio/` (requires JWT), guest uses
`/api/guest/audio/` (public).

---

### 3.7 `VideoService.java`

**Location:** `src/main/java/ak/dev/khi_archive_platform/platform/service/video/VideoService.java`

```java
// BEFORE
.videoFileUrl(video.getVideoFileUrl())

// AFTER
.videoFileUrl("/api/video/" + video.getVideoCode() + "/stream")
```

---

### 3.8 `ImageService.java`

**Location:** `src/main/java/ak/dev/khi_archive_platform/platform/service/image/ImageService.java`

```java
// BEFORE
.imageFileUrl(image.getImageFileUrl())

// AFTER
.imageFileUrl("/api/image/" + image.getImageCode() + "/view")
```

---

### 3.9 DTO Comment Updates

The Javadoc on the three guest DTOs was updated to reflect that
these fields now carry a **relative API path**, not an S3 URL.

**`GuestAudioDTO.java`**
```java
// BEFORE
/** Public S3 URL of the audio asset. */
private String audioFileUrl;

// AFTER
/** Relative API stream path — e.g. {@code /api/guest/audio/AUD-001/stream}.
 *  The frontend must prepend the API base URL. The actual S3 URL is
 *  never exposed; all bytes are proxied through the backend. */
private String audioFileUrl;
```

Same update applied to `GuestVideoDTO.java` and `GuestImageDTO.java`.

---

### 3.10 `SecurityConfig.java`

**Location:** `src/main/java/ak/dev/khi_archive_platform/user/configs/SecurityConfig.java`

No rule changes — the existing `/api/guest/**` rule already covers all new
public stream endpoints. Only the comment was improved:

```java
// BEFORE
.requestMatchers("/api/guest/**").permitAll()

// AFTER
// This also covers the media stream proxies:
//   /api/guest/audio/{code}/stream
//   /api/guest/video/{code}/stream
//   /api/guest/image/{code}/view
// No S3 URL is ever sent to the browser — bytes are proxied
// through the API, gated by removedAt IS NULL checks.
.requestMatchers("/api/guest/**").permitAll()
```

---

## 4. How Streaming Works

### 4.1 Audio Range Requests

```
Browser loads <audio src="/api/guest/audio/AUD-001/stream">

Step 1 — Initial load (no Range header):
  Browser → GET /api/guest/audio/AUD-001/stream
  Backend → HEAD S3 for file size (zero bytes downloaded)
  Backend → GET S3 (bytes=0 to end)
  Browser ← 200 OK, Content-Type: audio/mpeg, Content-Length: 5242880

Step 2 — User drags progress bar to 2:30:
  Browser → GET /api/guest/audio/AUD-001/stream
             Range: bytes=2621440-5242879
  Backend → GET S3 (bytes=2621440 to 5242879) — only those bytes
  Browser ← 206 Partial Content
             Content-Range: bytes 2621440-5242879/5242880
             (only the requested window, rest untouched)
```

### 4.2 Video Chunk Streaming

```
Browser loads <video src="/api/guest/video/VID-001/stream">

Step 1 — Initial load (no Range):
  Backend returns ONLY first 2 MB (not the whole file!)
  Browser ← 206 Partial Content
             Content-Range: bytes 0-2097151/524288000
             (browser now knows total=500 MB)

Step 2 — Buffer ahead (browser automatic):
  Browser → Range: bytes=2097152-4194303
  Backend → fetches only those 2 MB from S3
  Browser ← 206 Partial Content

Step 3 — User seeks to 1:23:45 (large jump):
  Browser → Range: bytes=450000000-452097151
  Backend → fetches only those 2 MB from S3
  (bytes 0–449999999 never downloaded — 86% of the file untouched)
```

### 4.3 Image ETag Caching

```
First visit to archive gallery page:
  Browser → GET /api/guest/image/IMG-001/view
  Backend → GET S3 (full image)
  Browser ← 200 OK, ETag: "9f3a2c", Cache-Control: public, max-age=3600
            (image cached in browser for 1 hour)

Same visit, second image load (browser cache valid):
  Browser does NOT send any request — uses memory cache instantly

Next day (cache expired):
  Browser → GET /api/guest/image/IMG-001/view
             If-None-Match: "9f3a2c"
  Backend → ETag matches! (no S3 download)
  Browser ← 304 Not Modified  (0 bytes sent, near-instant)
```

---

## 5. Response Headers

Every stream response includes these headers:

| Header | Example Value | Purpose |
|--------|--------------|---------|
| `Content-Type` | `audio/mpeg` | Browser knows how to play it |
| `Accept-Ranges` | `bytes` | Tells browser it CAN seek (required for `<audio>`/`<video>`) |
| `Content-Range` | `bytes 0-2097151/5242880` | "You got bytes 0–2M of a 5M file" |
| `Content-Length` | `2097152` | How many bytes in THIS response |
| `Content-Disposition` | `inline; filename="Song Title.mp3"` | Play in browser (not force-download), human-readable name |
| `Cache-Control` | `public, max-age=300` | How long browser/CDN can cache |
| `ETag` | `"9f3a2c"` | (images only) Cache fingerprint for 304 |
| `X-Content-Type-Options` | `nosniff` | Prevent browser MIME sniffing attacks |

---

## 6. Security Model

| Threat | How It's Blocked |
|--------|-----------------|
| **User copies stream URL** | The URL is `/api/guest/audio/AUD-001/stream` — accessing it just works for public content (by design). But it reveals no S3 URL and no other records. |
| **User inspects Network tab in DevTools** | Sees only `/api/guest/audio/AUD-001/stream`. The S3 URL exists only inside the backend process. |
| **Browser right-click → Save As** | `Content-Disposition: inline` tells the browser this is for display, not download. Combined with `controlsList="nodownload"` on the HTML element, the button is hidden. |
| **User pastes URL in `wget` / `curl`** | Public content is accessible — this is intentional for public records. But they get one file, not the S3 bucket. |
| **Bulk scraping all audio codes** | Rate-limit the `/api/guest/**/stream` routes with Bucket4j (see Phase 2). |
| **Accessing deleted content** | Guest routes use `findByAudioCodeAndRemovedAtIsNull()` — deleted records return `404 Not Found`. |
| **Admin content leaks to public** | Admin routes (`/api/audio/*/stream`) require a valid JWT. Missing token → `401 Unauthorized`. |
| **CDN caches admin content** | Admin routes return `Cache-Control: no-store, private`. CDN never stores these. |
| **Old S3 URLs shared before this change** | Those direct S3 URLs still work **if your bucket is public**. To complete the protection: set your S3 bucket to **fully private** (block all public access). The backend fetches using IAM credentials — it still works. The old leaked URLs stop working. |

> ⚠️ **Action required:** Go to your S3 bucket → Permissions → Block Public Access → **Enable all four options**. This is the final step that makes the protection complete.

---

## 7. Frontend Integration Guide

### 7.1 Setup

Create a helper function once, use everywhere:

```js
// In your API config file (e.g., src/config/api.js)
const API_BASE = import.meta.env.VITE_API_BASE_URL;
// or: const API_BASE = process.env.REACT_APP_API_BASE_URL;
// Example: 'https://api.khi-archive.com'

export const mediaUrl = (path) => {
  if (!path) return null;
  if (path.startsWith('http')) return path;  // safety: already absolute
  return `${API_BASE}${path}`;
};
```

The `audioFileUrl` / `videoFileUrl` / `imageFileUrl` fields from the API now
look like `/api/guest/audio/AUD-001/stream`. Use `mediaUrl()` to turn them
into a full URL.

### 7.2 Audio Player

```jsx
// React — Public guest page
function AudioPlayer({ audio }) {
  // audio.audioFileUrl = "/api/guest/audio/AUD-001/stream"
  const src = mediaUrl(audio.audioFileUrl);

  return (
    <audio
      controls
      controlsList="nodownload"     // hides browser's built-in download button
      preload="metadata"            // load only duration/title, not full file
      src={src}
    />
  );
}
```

```html
<!-- Vanilla HTML — same idea -->
<audio
  controls
  controlsList="nodownload"
  preload="metadata"
  src="https://api.khi-archive.com/api/guest/audio/AUD-001/stream"
></audio>
```

The browser automatically:
- Sends `Range` headers when the user seeks
- Shows duration from metadata
- Buffers only what it needs

### 7.3 Video Player

```jsx
// React — Public guest page
function VideoPlayer({ video }) {
  // video.videoFileUrl = "/api/guest/video/VID-001/stream"
  const src = mediaUrl(video.videoFileUrl);

  return (
    <video
      controls
      controlsList="nodownload nofullscreen"
      preload="metadata"
      playsInline                                      // mobile: play inline, not fullscreen
      src={src}
      onContextMenu={(e) => e.preventDefault()}        // disable right-click save-as
    />
  );
}
```

> **Why `206 Partial Content` on first load is correct:**  
> When the browser opens the `<video>`, it sends no Range header initially.
> The backend returns a `206` with only the first 2 MB. The browser sees the
> `Content-Range` header and learns the total file size. From then on it sends
> proper Range requests automatically. This is standard behaviour for any
> video streaming server (Netflix, YouTube, etc. all do this).

### 7.4 Image Display

```jsx
// React — Public guest gallery
function ArchiveImage({ image, alt }) {
  // image.imageFileUrl = "/api/guest/image/IMG-001/view"
  const src = mediaUrl(image.imageFileUrl);

  return (
    <img
      src={src}
      alt={alt}
      loading="lazy"                             // browser lazy-loads off-screen images
      onContextMenu={(e) => e.preventDefault()}  // minor speed bump against right-click save
      draggable={false}                          // prevent drag-to-desktop
    />
  );
}
```

```jsx
// React — For list pages with many images: use a thumbnail approach
// (show low-res preview, load full on click)
function ImageCard({ image }) {
  const [showFull, setShowFull] = useState(false);
  const src = mediaUrl(image.imageFileUrl);

  return (
    <div onClick={() => setShowFull(true)}>
      {showFull
        ? <img src={src} alt={image.originalTitle} />
        : <div className="placeholder" />   // skeleton until clicked
      }
    </div>
  );
}
```

### 7.5 Admin Panel

Admin routes require the JWT token in the `Authorization` header.
A standard `<audio src="...">` or `<video src="...">` element cannot send
custom headers, so for admin previews use a **blob URL**:

```js
// React hook — fetch admin media as a blob, return a local URL
import { useState, useEffect } from 'react';

export function useAdminMediaUrl(apiPath, token) {
  const [blobUrl, setBlobUrl] = useState(null);

  useEffect(() => {
    if (!apiPath || !token) return;

    let objectUrl;
    fetch(`${API_BASE}${apiPath}`, {
      headers: { Authorization: `Bearer ${token}` }
    })
      .then((res) => {
        if (!res.ok) throw new Error(`Stream error: ${res.status}`);
        return res.blob();
      })
      .then((blob) => {
        objectUrl = URL.createObjectURL(blob);
        setBlobUrl(objectUrl);
      })
      .catch(console.error);

    // Clean up blob URL when component unmounts (prevents memory leak)
    return () => {
      if (objectUrl) URL.revokeObjectURL(objectUrl);
    };
  }, [apiPath, token]);

  return blobUrl;
}

// Usage in a component:
function AdminAudioPreview({ audio, token }) {
  // audio.audioFileUrl = "/api/audio/AUD-001/stream"  (admin path, not /guest/)
  const blobUrl = useAdminMediaUrl(audio.audioFileUrl, token);

  return blobUrl
    ? <audio controls src={blobUrl} />
    : <p>Loading preview...</p>;
}
```

> **Note on admin video:** Large video files will be slow to fully download
> before playback starts. For the admin panel, it's acceptable. For
> production-scale video, Phase 2 (HLS) is the right solution.

---

## 8. API Endpoint Reference

### Public (no authentication required)

| Method | Endpoint | Description |
|--------|----------|-------------|
| `GET` | `/api/guest/audio/{audioCode}/stream` | Stream audio bytes. Supports `Range` header. Only non-deleted records. |
| `GET` | `/api/guest/video/{videoCode}/stream` | Stream video bytes. Supports `Range` header. Returns `206` always. Only non-deleted records. |
| `GET` | `/api/guest/image/{imageCode}/view` | Serve image bytes. Supports `If-None-Match` / `304`. Only non-deleted records. |

### Authenticated (JWT Bearer token required)

| Method | Endpoint | Description |
|--------|----------|-------------|
| `GET` | `/api/audio/{audioCode}/stream` | Stream audio. Same as guest but includes soft-deleted records (for admin preview). |
| `GET` | `/api/video/{videoCode}/stream` | Stream video. Same as guest but includes soft-deleted records. |
| `GET` | `/api/image/{imageCode}/view` | Serve image. Same as guest but includes soft-deleted records. |
| `GET` | `/api/maqam/{maqamCode}/stream` | Stream maqam audio. Additional permission check: `maqam:read`. Already existed before this change. |

### Common response codes

| Code | Meaning |
|------|---------|
| `200 OK` | Full response (audio with no Range header, images) |
| `206 Partial Content` | Byte-range response (audio seek, all video responses) |
| `304 Not Modified` | Image ETag matched — browser uses its cached copy |
| `404 Not Found` | Record not found or soft-deleted (guest routes) |
| `401 Unauthorized` | Missing or invalid JWT (admin routes) |
| `500 Internal Server Error` | S3 fetch failed (logged server-side) |

---

## 9. What Was NOT Changed

To be 100% clear — **none of the existing API endpoints were changed**:

| Endpoint | Status |
|----------|--------|
| `GET /api/audio` | ✅ Unchanged |
| `GET /api/audio/{code}` | ✅ Unchanged |
| `POST /api/audio` (upload) | ✅ Unchanged |
| `PUT /api/audio/{code}` | ✅ Unchanged |
| `GET /api/video` | ✅ Unchanged |
| `GET /api/image` | ✅ Unchanged |
| `GET /api/guest/audios` | ✅ Unchanged |
| `GET /api/guest/videos` | ✅ Unchanged |
| `GET /api/guest/images` | ✅ Unchanged |
| All other `/api/**` routes | ✅ Unchanged |

**The only thing that changed in existing endpoints** is the _value_ of
`audioFileUrl` / `videoFileUrl` / `imageFileUrl` fields in responses:
- **Before:** `"https://bucket.s3.region.amazonaws.com/..."`
- **After:** `"/api/guest/audio/AUD-001/stream"`

The field **names** are identical. The response **shape** is identical.
Only the URL format changed.

---

## 10. Phase 2 Roadmap

The current implementation is **Phase 1** — private proxy streaming.
It solves the S3 URL exposure problem immediately without any infrastructure
changes.

Phase 2 adds industry-standard protection against someone recording the
proxied bytes:

### Phase 2A — HLS Streaming (Audio & Video)

Instead of serving the raw MP3/MP4, transcode to **HLS** (HTTP Live Streaming):

```
Input file:  song.mp3  (one downloadable file)

ffmpeg output:
  song.m3u8          (playlist — just a text file listing segments)
  segment_000.ts     (10-second clip — useless alone)
  segment_001.ts     (10-second clip — useless alone)
  segment_002.ts     (10-second clip — useless alone)
  ...
```

Each `.ts` segment is **10 seconds** of audio. No single request gets "the
file" — the user gets a stream of tiny clips. Recording them all requires
effort, and watermarking each segment per-user makes tracking trivial.

```
New API routes (Phase 2):
  GET /api/guest/audio/{code}/playlist.m3u8    → serves the playlist
  GET /api/guest/audio/{code}/segment_003.ts   → serves one 10s chunk

Frontend change (Phase 2):
  import Hls from 'hls.js';

  const hls = new Hls();
  hls.loadSource(`${API_BASE}/api/guest/audio/AUD-001/playlist.m3u8`);
  hls.attachMedia(audioElement);
```

**ffmpeg command to transcode audio to HLS:**
```bash
ffmpeg -i input.mp3 \
  -codec:a aac -b:a 128k \
  -hls_time 10 \
  -hls_list_size 0 \
  -hls_segment_filename "segment_%03d.ts" \
  playlist.m3u8
```

### Phase 2B — Image Tiling (Books & High-Res Images)

For book scans and high-resolution archive images, use **libvips** to generate
a Deep Zoom tile pyramid:

```bash
vips dzsave input.tiff output --tile-size=256
# Creates: output.dzi + output_files/0/0_0.jpg, output_files/1/...
```

```
New API routes (Phase 2):
  GET /api/guest/image/{code}/tile/{z}/{x}_{y}.jpg   → one 256×256 tile

Frontend change (Phase 2):
  import OpenSeadragon from 'openseadragon';

  OpenSeadragon({
    id: 'viewer',
    tileSources: {
      type: 'zoomifytileservice',
      tilesUrl: `${API_BASE}/api/guest/image/IMG-001/tile/`
    }
  });
```

The viewer requests only the tiles visible at the current zoom level —
a 500 MB book scan serves ~50 KB per page view.

### Phase 2C — Rate Limiting

Add per-IP rate limiting to prevent bulk scraping of stream endpoints:

```xml
<!-- pom.xml dependency -->
<dependency>
  <groupId>com.github.vladimir-bukhtoyarov</groupId>
  <artifactId>bucket4j-core</artifactId>
</dependency>
```

```java
// StreamRateLimitFilter.java
// Allow 60 stream requests per minute per IP
// Block (429 Too Many Requests) if exceeded
```

---

## Summary — Files Changed

| File | Type | Change |
|------|------|--------|
| `S3Service.java` | Modified | Added `openStream()`, `openStreamRange()`, `getObjectSize()` |
| `AudioStreamAPI.java` | **New** | Proxy audio endpoint with Range support + caching |
| `VideoStreamAPI.java` | **New** | Proxy video endpoint, 2 MB chunk strategy, always 206 |
| `ImageStreamAPI.java` | **New** | Proxy image endpoint with ETag/304 support |
| `GuestMapper.java` | Modified | 3 lines: S3 URLs → stream endpoint paths (guest) |
| `AudioService.java` | Modified | 1 line: S3 URL → stream endpoint path (admin) |
| `VideoService.java` | Modified | 1 line: S3 URL → stream endpoint path (admin) |
| `ImageService.java` | Modified | 1 line: S3 URL → stream endpoint path (admin) |
| `GuestAudioDTO.java` | Modified | Javadoc comment updated |
| `GuestVideoDTO.java` | Modified | Javadoc comment updated |
| `GuestImageDTO.java` | Modified | Javadoc comment updated |
| `SecurityConfig.java` | Modified | Comment clarification only |

**Total: 3 new files, 9 modified files. Build: ✅ SUCCESS.**
