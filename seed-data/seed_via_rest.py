#!/usr/bin/env python3
"""
Walks seed-data/*.json and POSTs every record through the real REST endpoints
(/api/category/bulk, /api/project/bulk, /api/audio/bulk, …) instead of the
in-process SeedDataLoader. Use this when you want the full audit-log + cache
side effects of a real HTTP call (which the SeedDataLoader bypasses).

Run:
  python3 seed-data/seed_via_rest.py \
      --base http://localhost:8080 \
      --username akar \
      --password 'secret'

Optional flags:
  --dir <path>             Seed dir (default ./seed-data)
  --only categories,projects,audios,videos,texts,images,persons
  --skip-persons           Persons require multipart with a portrait file part
                           Skipped by default; pass --include-persons to attempt.
  --include-persons        Try to POST persons (downloads mediaPortrait or uses
                           a generated placeholder when the URL fails).
  --batch <n>              Chunk size for /bulk POSTs (default 200).
  --dry-run                Don't send; write transformed payloads to
                           seed-data/rest-payloads/ for Postman import.
  --insecure               Skip TLS verification.

What it does NOT do:
  - Restore the original *Code values for audios/videos/texts/images. The REST
    API generates codes server-side; the in-process seeder preserves them.
    Run SeedDataLoader (app.seed.load=true) if you need the exact codes back.
  - Re-upload media files. Media URLs in the JSON are passed through as
    *FileUrl strings (S3 / Wikimedia URLs work as-is).
"""

from __future__ import annotations

import argparse
import json
import os
import sys
from pathlib import Path
from typing import Any, Iterable

try:
    import requests
except ImportError:
    requests = None  # Only needed when actually sending; --dry-run works without.


# ─── Field defaults the /bulk endpoints insist on but the seed JSONs lack ───
# audio:  RAW or MASTER
# video:  RAW, MASTER, RESTORED, ARCHIVE, ORIGINAL, 4K_MASTER, PROFESSIONAL
# text:   RAW, MASTER, RESTORED, ARCHIVE, ORIGINAL, DIGITIZED, PROFESSIONAL
# image:  RAW, MASTER, RESTORED, ARCHIVE, ORIGINAL, HIGH_RES, PROFESSIONAL
MEDIA_DEFAULTS = {
    "audios":  {"audioVersion": "RAW",  "versionNumber": 1, "copyNumber": 1},
    "videos":  {"videoVersion": "RAW",  "versionNumber": 1, "copyNumber": 1},
    "texts":   {"textVersion":  "RAW",  "versionNumber": 1, "copyNumber": 1},
    "images":  {"imageVersion": "RAW",  "versionNumber": 1, "copyNumber": 1},
}


# ─── Transformers (JSON row → REST DTO shape) ──────────────────────────────

def to_category_dto(r: dict) -> dict:
    return {
        "categoryCode": r["categoryCode"],
        "name":         r.get("name"),
        "description":  r.get("description"),
        "keywords":     r.get("keywords") or [],
    }


def to_project_dto(r: dict) -> dict:
    person = r.get("person") or {}
    cats = r.get("categories") or []
    return {
        "projectName":   r.get("projectName"),
        "personCode":    person.get("personCode"),
        "categoryCodes": [c["categoryCode"] for c in cats if c.get("categoryCode")],
        "description":   r.get("description"),
        "tags":          r.get("tags") or [],
        "keywords":      r.get("keywords") or [],
    }


def to_media_dto(kind: str, r: dict, copy_number: int = 1) -> dict:
    """
    Strip the seed-only fields (id, *Code, nested person/categories) and merge
    in the version/copy defaults the bulk DTO validators require.

    The server generates the audio/video/text/image business code from
    {projectCode}_{kind}_{version}_V{versionNumber}_Copy({copyNumber})_NNNNNN,
    so two rows in the same project that share the same (version, version#,
    copy#) collide on the unique constraint. We bump copy_number per row
    within a project to keep generated codes unique.
    """
    drop = {"id", "audioCode", "videoCode", "textCode", "imageCode",
            "projectName", "person", "personMediaPortrait", "categories",
            "mediaCounts"}
    payload = {k: v for k, v in r.items() if k not in drop}
    payload.update(MEDIA_DEFAULTS[kind])
    payload["copyNumber"] = copy_number
    return payload


def _parent_code(r: dict) -> str:
    """
    Mirror the server's parentCode derivation in *Service.createAll:
    person.personCode if any, else first category's categoryCode.
    The server's per-project seq counter is keyed by project.id, but the
    parentCode is shared across projects of the same person/category — so
    {parent}_Copy(1)_000001 can collide between two projects that share a
    parent. We bump copyNumber per parentCode globally to keep codes unique.
    """
    person = r.get("person") or {}
    pc = person.get("personCode")
    if pc:
        return pc.upper()
    cats = r.get("categories") or []
    if cats and cats[0].get("categoryCode"):
        return cats[0]["categoryCode"].upper()
    return r.get("projectCode") or ""


def media_rows_with_copy_numbers(kind: str, rows: list[dict]) -> list[dict]:
    """Assign a per-parentCode running copyNumber so codes don't collide."""
    counter: dict[str, int] = {}
    out: list[dict] = []
    for r in rows:
        parent = _parent_code(r)
        counter[parent] = counter.get(parent, 0) + 1
        out.append(to_media_dto(kind, r, copy_number=counter[parent]))
    return out


def to_person_dto(r: dict) -> dict:
    """API takes year/month/day separately; seed JSON has an ISO date string."""
    def split(date_str: str | None) -> tuple[int | None, int | None, int | None]:
        if not date_str:
            return None, None, None
        try:
            y, m, d = (int(p) for p in date_str.split("-")[:3])
            return y, m, d
        except Exception:
            return None, None, None

    by, bm, bd = split(r.get("dateOfBirth"))
    dy, dm, dd = split(r.get("dateOfDeath"))
    return {
        "personCode":       r["personCode"],
        "fullName":         r.get("fullName") or r.get("personCode"),
        "nickname":         r.get("nickname"),
        "romanizedName":    r.get("romanizedName"),
        "gender":           r.get("gender"),
        "personType":       r.get("personType") or [],
        "region":           r.get("region"),
        "dateOfBirthYear":  by, "dateOfBirthMonth": bm, "dateOfBirthDay": bd,
        "placeOfBirth":     r.get("placeOfBirth"),
        "dateOfDeathYear":  dy, "dateOfDeathMonth": dm, "dateOfDeathDay": dd,
        "placeOfDeath":     r.get("placeOfDeath"),
        "description":      r.get("description"),
        "tag":              r.get("tag") or [],
        "keywords":         r.get("keywords") or [],
    }


# ─── HTTP helpers ───────────────────────────────────────────────────────────

def login(s: requests.Session, base: str, user: str, pw: str) -> None:
    """
    Logs in and pins the JWT onto the session as an Authorization: Bearer
    header. We can't rely on the khi_auth_token cookie because the backend
    sets it with Secure=true (default), and the requests library refuses to
    replay Secure cookies over plain http://localhost. The backend's
    JWTAuthenticationFilter#resolveToken accepts either source, so the Bearer
    header is the simpler path here.
    """
    r = s.post(f"{base}/api/auth/login",
               json={"username": user, "password": pw},
               headers={"Accept": "application/json"})
    r.raise_for_status()

    # Prefer the cookie value (works even when 'Secure' kept requests from
    # storing it for replay — requests still parses it into s.cookies).
    token = s.cookies.get("khi_auth_token")
    if not token:
        # Fall back to a token field in the JSON body if the API also returns one.
        body = safe_json(r) or {}
        token = body.get("token") or body.get("accessToken") or body.get("jwt")
    if not token:
        sys.exit(f"login succeeded ({r.status_code}) but no JWT found in cookie or body: {r.text[:300]}")

    s.headers["Authorization"] = f"Bearer {token}"
    print(f"[auth] logged in as {user} (bearer token attached)")


def chunks(seq: list, n: int) -> Iterable[list]:
    for i in range(0, len(seq), n):
        yield seq[i:i + n]


def bulk_post(s: requests.Session, base: str, path: str, items: list[dict],
              batch: int, label: str) -> None:
    total, sent, errs = len(items), 0, 0
    for i, batch_items in enumerate(chunks(items, batch), start=1):
        r = s.post(f"{base}{path}", json=batch_items,
                   headers={"Accept": "application/json"})
        if r.status_code >= 400:
            errs += len(batch_items)
            print(f"[{label}] batch {i}: HTTP {r.status_code} — {r.text[:400]}")
            continue
        sent += len(batch_items)
        body = safe_json(r)
        if isinstance(body, dict):
            # BulkCreateResult: {requested, inserted, skipped, elapsedMs}.
            # CategoryService.BulkCreateResult uses {created, skipped} — fall
            # back so both shapes print cleanly.
            inserted = body.get("inserted", body.get("created"))
            print(f"[{label}] batch {i}: inserted={inserted} "
                  f"skipped={body.get('skipped')} "
                  f"requested={body.get('requested', len(batch_items))}")
        else:
            print(f"[{label}] batch {i}: {r.status_code}")
    print(f"[{label}] done — sent {sent}/{total}, errors on {errs}")


def safe_json(r: requests.Response) -> Any:
    try:
        return r.json()
    except Exception:
        return None


# ─── Persons (multipart, one at a time) ─────────────────────────────────────

PLACEHOLDER_PNG = bytes.fromhex(
    "89504e470d0a1a0a0000000d49484452000000010000000108060000001f15c4"
    "890000000a49444154789c6300010000000500010d0a2db40000000049454e44ae426082"
)


def fetch_portrait(url: str | None, timeout: int = 8) -> tuple[bytes, str]:
    """Returns (bytes, content-type). Falls back to a 1×1 PNG on any failure."""
    if not url:
        return PLACEHOLDER_PNG, "image/png"
    try:
        r = requests.get(url, timeout=timeout)
        if r.ok and r.content:
            ctype = r.headers.get("Content-Type", "image/jpeg").split(";")[0]
            return r.content, ctype
    except Exception:
        pass
    return PLACEHOLDER_PNG, "image/png"


def post_person(s: requests.Session, base: str, dto: dict, portrait_url: str | None):
    blob, ctype = fetch_portrait(portrait_url)
    ext = "png" if "png" in ctype else "jpg"
    files = {
        "data":          (None, json.dumps(dto), "application/json"),
        "mediaPortrait": (f"{dto['personCode']}.{ext}", blob, ctype),
    }
    r = s.post(f"{base}/api/person", files=files,
               headers={"Accept": "application/json"})
    return r


# ─── Main ───────────────────────────────────────────────────────────────────

ORDER = ["categories", "persons", "projects", "audios", "videos", "texts", "images"]

BULK_PATH = {
    "categories": "/api/category/bulk",
    "projects":   "/api/project/bulk",
    "audios":     "/api/audio/bulk",
    "videos":     "/api/video/bulk",
    "texts":      "/api/text/bulk",
    "images":     "/api/image/bulk",
}

TRANSFORM = {
    "categories": to_category_dto,
    "projects":   to_project_dto,
    "audios":     lambda r: to_media_dto("audios", r),
    "videos":     lambda r: to_media_dto("videos", r),
    "texts":      lambda r: to_media_dto("texts",  r),
    "images":     lambda r: to_media_dto("images", r),
}

MEDIA_KINDS = {"audios", "videos", "texts", "images"}


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default="http://localhost:8080")
    ap.add_argument("--username", required=False)
    ap.add_argument("--password", required=False)
    ap.add_argument("--dir", default=str(Path(__file__).resolve().parent))
    ap.add_argument("--only", default=",".join(ORDER),
                    help="Comma-separated subset of: " + ",".join(ORDER))
    ap.add_argument("--include-persons", action="store_true",
                    help="Try POSTing persons via multipart (default: skip).")
    ap.add_argument("--batch", type=int, default=200)
    ap.add_argument("--dry-run", action="store_true",
                    help="Write payloads to seed-data/rest-payloads/ and exit.")
    ap.add_argument("--insecure", action="store_true")
    args = ap.parse_args()

    seed_dir = Path(args.dir)
    if not seed_dir.is_dir():
        sys.exit(f"seed dir not found: {seed_dir}")

    selected = [s.strip() for s in args.only.split(",") if s.strip() in ORDER]
    if "persons" in selected and not args.include_persons:
        selected.remove("persons")
    if not selected:
        sys.exit("nothing to do — --only filtered everything out")

    # Load every JSON up front so a missing file fails fast.
    loaded: dict[str, list[dict]] = {}
    for name in selected:
        path = seed_dir / f"{name}.json"
        if not path.exists():
            print(f"[{name}] {path.name} not found — skipping")
            continue
        with path.open(encoding="utf-8") as f:
            loaded[name] = json.load(f)
        print(f"[{name}] read {len(loaded[name])} rows from {path.name}")

    # Dry-run: dump transformed payloads to disk for Postman import.
    if args.dry_run:
        out = seed_dir / "rest-payloads"
        out.mkdir(exist_ok=True)
        for name, rows in loaded.items():
            if name == "persons":
                payload = [to_person_dto(r) for r in rows]
            elif name in MEDIA_KINDS:
                payload = media_rows_with_copy_numbers(name, rows)
            else:
                payload = [TRANSFORM[name](r) for r in rows]
            (out / f"{name}.json").write_text(
                json.dumps(payload, ensure_ascii=False, indent=2),
                encoding="utf-8")
            print(f"[dry-run] wrote {out / (name + '.json')}")
        return 0

    if not args.username or not args.password:
        sys.exit("--username and --password are required (or use --dry-run)")
    if requests is None:
        sys.exit("requests is required to POST: pip install requests")

    s = requests.Session()
    if args.insecure:
        s.verify = False
        import urllib3
        urllib3.disable_warnings()

    login(s, args.base, args.username, args.password)

    # Order matters: FKs flow categories → persons → projects → media.
    for name in ORDER:
        if name not in loaded:
            continue
        rows = loaded[name]

        if name == "persons":
            print(f"[persons] POSTing {len(rows)} multipart records — this is slow")
            ok = fail = 0
            for r in rows:
                dto = to_person_dto(r)
                resp = post_person(s, args.base, dto, r.get("mediaPortrait"))
                if resp.status_code >= 400:
                    fail += 1
                    print(f"  ! {dto['personCode']}: HTTP {resp.status_code} "
                          f"— {resp.text[:200]}")
                else:
                    ok += 1
            print(f"[persons] done — ok={ok} fail={fail}")
            continue

        if name in MEDIA_KINDS:
            items = media_rows_with_copy_numbers(name, rows)
        else:
            items = [TRANSFORM[name](r) for r in rows]
        bulk_post(s, args.base, BULK_PATH[name], items, args.batch, name)

    return 0


if __name__ == "__main__":
    sys.exit(main())
