#!/usr/bin/env python3
"""Probe Wikipedia for which Kurdish-figure slugs resolve to articles with a portrait."""
import json
import time
import urllib.request
import urllib.parse
import urllib.error
import sys

CANDIDATES = [
    # Musicians
    "Hassan_Zirak", "Mohammad_Mamle", "Şivan_Perwer", "Ciwan_Haco", "Aram_Tigran",
    "Ahmet_Kaya", "Ayşe_Şan", "Tahsin_Taha", "Mazhar_Khaleqi", "Naser_Razazi",
    "Ali_Mardan_(singer)", "Ali_Mardan", "Karwan_Kamal", "Adnan_Karim",
    "Mihemed_Şêxo", "Sebzîye_Akreyî", "Zakaria_Abdulla", "Aziz_Waisi",
    "Mihemed_Arif_Cizîrî", "Mihemed_Arif_Cizrawî",
    # Poets / writers
    "Sherko_Bekas", "Cigerxwîn", "Ehmedê_Xanî", "Ahmad_Khani",
    "Nali_(poet)", "Mahwi_(poet)", "Piremerd", "Abdulla_Goran",
    "Hejar_Mukriyani", "Hêmin_Mukriyani", "Faqi_Tayran", "Melayê_Cizîrî",
    "Mela_Mahmud_Bayazidi", "Mawlawi_Tawagozi", "Abdulla_Pashew",
    "Latif_Halmat", "Refiq_Sabir", "Bekes_(poet)", "Mela_Mistefa_Yamulki",
    "Renas_Jiyan",
    # Politicians / leaders
    "Masoud_Barzani", "Mustafa_Barzani", "Jalal_Talabani", "Qazi_Muhammad",
    "Abdul_Rahman_Ghassemlou", "Sheikh_Mahmud_Barzanji", "Nechirvan_Barzani",
    "Sheikh_Ubeydullah", "Sheikh_Said", "Sadegh_Sharafkandi",
    "Idris_Barzani", "Ibrahim_Ahmad", "Salahaddin_Bahaaddin",
    "Yılmaz_Güney", "Ehmed_Berzanci",
    # Religious / scholars
    "Said_Nursi", "Celadet_Bedir_Khan", "Kamuran_Alî_Bedir_Khan",
    "Tofiq_Wahby", "Sadiq_Bahaeddin", "Saeed_Sirjani",
    # Additional poets/musicians
    "Bekas", "Goran_(poet)", "Mela_Cizîrî", "Aram_Dîkran",
    "Marif_Cizrawi", "Mihemed_Arif", "Mihemed_Mamle",
]

def fetch(title, max_retries=3):
    url = f"https://en.wikipedia.org/api/rest_v1/page/summary/{urllib.parse.quote(title, safe='')}"
    for attempt in range(max_retries):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": "khi-seed-probe/1.0 (contact: archive@example.org)"})
            with urllib.request.urlopen(req, timeout=10) as r:
                data = json.loads(r.read().decode("utf-8"))
                if data.get("type") == "disambiguation":
                    return None
                img = (data.get("originalimage") or {}).get("source") or (data.get("thumbnail") or {}).get("source")
                return {
                    "title": data.get("title"),
                    "image": img,
                    "extract": data.get("extract", "")[:300],
                    "wikipedia_url": data.get("content_urls", {}).get("desktop", {}).get("page"),
                }
        except urllib.error.HTTPError as e:
            if e.code == 404:
                return None
            if e.code == 429:
                wait = 4 * (attempt + 1)
                time.sleep(wait)
                continue
            return f"http {e.code}"
        except Exception as e:
            return f"err: {e}"
    return "rate-limited"

results = {}
for slug in CANDIDATES:
    time.sleep(0.6)  # polite throttle, keeps us under Wikipedia's REST quota
    r = fetch(slug)
    if r is None:
        status = "404"
    elif isinstance(r, str):
        status = r
    elif not r.get("image"):
        status = "no-image"
    else:
        status = "OK"
    print(f"  {status:<10} {slug:<35} {r.get('title') if isinstance(r, dict) else ''}")
    results[slug] = r

with open("probe_results.json", "w", encoding="utf-8") as f:
    json.dump(results, f, ensure_ascii=False, indent=2)
print(f"\n  saved to probe_results.json")
